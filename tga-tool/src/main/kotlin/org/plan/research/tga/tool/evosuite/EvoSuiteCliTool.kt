package org.plan.research.tga.tool.evosuite

import org.apache.commons.cli.Option
import org.plan.research.tga.core.config.TgaConfig
import org.plan.research.tga.core.config.buildOptions
import org.plan.research.tga.core.dependency.Dependency
import org.plan.research.tga.core.tool.TestGenerationTool
import org.plan.research.tga.core.tool.TestSuite
import org.plan.research.tga.core.util.TGA_PIPELINE_HOME
import org.vorpal.research.kthelper.buildProcess
import org.vorpal.research.kthelper.getJavaPath
import org.vorpal.research.kthelper.logging.log
import org.vorpal.research.kthelper.resolve
import org.vorpal.research.kthelper.terminateOrKill
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.bufferedWriter
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.streams.toList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * For my experiments I use already generated LLM tests from a different experiment,
 * and I need to provide some additional arguments to locate them.
 * This class is supposed to parse the additional specific commands I intend if they are not specified, the tool should
 * function as if you are inputting arguments directly to EvoSuite CLI.
 */
private class EvoSuiteCliParser(args: List<String>) : TgaConfig("EvoSuite", options, args.toTypedArray()) {

    companion object {
        val options = buildOptions {
            addOption(
                Option("h", "help", false, "print this help and quit").also {
                    it.isRequired = false
                }
            )

            addOption(
                Option(null, "llmTestLocation", true, "Location where llm tests should be.").also {
                    it.isRequired = false
                }
            )

            addOption(
                Option(null, "llmTestName", true, "Name of the llm for which we should find tests.").also {
                    it.isRequired = false
                }
            )

            addOption(Option(null,
                "cliArg", true, "Arguments to be passed directly to EvoSuite cli.").also {
                it.isRequired = false
                }
            )
        }
    }

    /**
     * To make EvoSuite acknowledge LLM-generated tests which are located in a different place,
     * I need to add a classpath to them.
     * To do that,
     * I need an output directory path
     * which will inform me of which benchmark we are in and which run we are doing
     * since I have a different set of LLM tests for each run.
     * If, however, we do not need to add an LLM test and have simply supplied EvoSuite CLI args, then we return null.
     */
    fun getLlmTestClasspath(outputDirectory: Path) : Path? {
        if (!this.hasOption("llmTestLocation")) {
            return null
        } else {
            val currentBenchmark = outputDirectory.fileName.toString()
            val currentRun = outputDirectory.parent.fileName.toString().split("-").let { it[it.size-1] }
            val llmTestLocation = this.getCmdValue("llmTestLocation")
            val llmTestName = this.getCmdValue("llmTestName")

            val pathToResultsDirectory = Paths.get(llmTestLocation, "$llmTestName-$currentRun", currentBenchmark)

            if (Files.isDirectory(pathToResultsDirectory)) {
                return pathToResultsDirectory

            } else {
                log.debug("Path is not a directory. {}", pathToResultsDirectory)
                return null
            }
        }
    }
}

class EvoSuiteCliTool(private val args: List<String>) : TestGenerationTool {
    override val name: String = "EvoSuite"
    private val argParser = EvoSuiteCliParser(args)

    companion object {
        private const val EVOSUITE_VERSION = "master-1.2.1-SNAPSHOT-bugfix"
        private const val EVOSUITE_DEPENDENCY_VERSION = "1.0.6"
        private const val EVOSUITE_LOG = "evosuite.log"
        private val EVOSUITE_JAR_PATH = TGA_PIPELINE_HOME.resolve("lib", "evosuite-$EVOSUITE_VERSION.jar")
    }

    private lateinit var root: Path
    private lateinit var classPath: List<Path>
    private lateinit var outputDirectory: Path

    override fun init(root: Path, classPath: List<Path>) {
        this.root = root
        this.classPath = classPath
    }

    /**
     * Given a path to the llm tests, I need to find their canonical names, which I can do by looking at the .exec files
     * located in the directory of the llm tests. This is because of the specific organizational structure of the llm tests I use.
     */
    fun findCanonicalLlmTests(directory: Path): String {
        return directory.listDirectoryEntries().filter { it.fileName.toString().endsWith(".exec") }
            .joinToString(":") { it.fileName.toString().replace(".exec", "") }
    }

    override fun run(target: String, timeLimit: Duration, outputDirectory: Path) {
        this.outputDirectory = outputDirectory.also {
            it.toFile().mkdirs()
        }

        var remainingUserSpecifiedArgs = argParser.getCmdValues("cliArg")

        argParser.getLlmTestClasspath(outputDirectory)?.let {
            (classPath as MutableList).add(it)
            remainingUserSpecifiedArgs = remainingUserSpecifiedArgs + "-Dselected_junit=${findCanonicalLlmTests(it)}"
        }



        var process: Process? = null
        try {
            process = buildProcess(
                getJavaPath().toString(), "-jar",
                EVOSUITE_JAR_PATH.toString(),
                "-generateMOSuite",
                "-base_dir", outputDirectory.toString(),
                "-projectCP", classPath.joinToString(File.pathSeparator),
                "-Dnew_statistics=false",
                "-Dsearch_budget=${timeLimit.inWholeSeconds}",
                "-class", target,
                "-Dcatch_undeclared_exceptions=false",
                "-Dtest_naming_strategy=COVERAGE",
                "-Dalgorithm=DYNAMOSA",
                "-Dno_runtime_dependency=true",
                "-Dcriterion=LINE:BRANCH:EXCEPTION:WEAKMUTATION:OUTPUT:METHOD:METHODNOEXCEPTION:CBRANCH",
                *remainingUserSpecifiedArgs,
            ) {
                redirectErrorStream(true)
                log.debug("Starting EvoSuite with command: {}", command())
            }
            log.debug("Configure reader for the EvoSuite process")
            outputDirectory.resolve(EVOSUITE_LOG).bufferedWriter().use { writer ->
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val line = reader.readLine() ?: break
                    writer.write(line)
                    writer.write("\n")
                }
            }
            log.debug("Waiting for the EvoSuite process...")
            process.waitFor()
            log.debug("EvoSuite process has merged")
        } catch (e: InterruptedException) {
            log.error("EvoSuite was interrupted on target $target")
        } catch (e: Exception) {
            log.error("Other exception: ${e.toString()}")
            log.error(e.stackTrace.contentDeepToString())
        } finally {
            process?.terminateOrKill(attempts = 10U, waitTime = 500.milliseconds)
        }
    }

    override fun report(): TestSuite {
        val testSrcPath = outputDirectory.resolve("evosuite-tests")
        val originalTests = when {
            testSrcPath.exists() -> Files.walk(testSrcPath).filter { it.fileName.toString().endsWith(".java") }
                .map { testSrcPath.relativize(it).toString().replace(File.separatorChar, '.').removeSuffix(".java") }
                .toList()

            else -> emptyList()
        }
        val newTests = mutableListOf<String>()
        for (test in originalTests) {
            val testCode = testSrcPath.resolve(test.replace('.', File.separatorChar) + ".java")
                .toFile().readText()

            val header = testCode.substringBefore("public class")
            val testFunctions = testCode
                .removePrefix(header)
                .removePrefix("public class ${test.substringAfterLast('.')} {")
                .removeSuffix("\n")
                .removeSuffix("}")
                .trim()
                .split("@Test")
                .filter { it.isNotBlank() }
            for ((index, testFunction) in testFunctions.withIndex()) {
                val newTestName = "$test${index}"
                newTests += newTestName

                val newTestFile = testSrcPath.resolve(newTestName.replace('.', File.separatorChar) + ".java")
                newTestFile.parent.toFile().mkdirs()
                newTestFile.bufferedWriter().use {
                    it.write(header)
                    it.write("public class ${newTestName.substringAfterLast('.')} {")
                    it.write("@Test$testFunction")
                    it.write("}")
                }
            }
        }
        originalTests.forEach {
            testSrcPath.resolve(it.replace('.', File.separatorChar) + ".java").toFile().delete()
        }
        return TestSuite(
            testSrcPath,
            newTests,
            emptyList(),
            listOf(
                Dependency("junit", "junit", "4.13.2"),
                Dependency("org.evosuite", "evosuite-master", EVOSUITE_DEPENDENCY_VERSION),
                Dependency("org.evosuite", "evosuite-standalone-runtime", EVOSUITE_DEPENDENCY_VERSION),
            )
        )
    }
}
