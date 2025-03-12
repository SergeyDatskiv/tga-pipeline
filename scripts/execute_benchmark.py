#!/bin/python3
import argparse
import subprocess
import sys
import tempfile
from typing import Iterable

from generate_compose import EvoSuiteArgs
from generate_compose import KexArgs
from generate_compose import TestSparkArgs
from generate_compose import JazzerArgs
from generate_compose import ManualArgs
from generate_compose import Tool
from generate_compose import generate_compose

# Global parameters
RUNNER_IMAGE = "registry.jetbrains.team/p/automatically-generating-unit-tests/sdatskiv-tga-pipeline/tga-pipeline:runner-0.0.2" # "abdullin/tga-pipeline:runner-0.0.46"
TOOL_IMAGE = "registry.jetbrains.team/p/automatically-generating-unit-tests/sdatskiv-tga-pipeline/tga-pipeline:tools-0.0.2" # "abdullin/tga-pipeline:tools-0.0.46"
BENCHMARKS_FILE = "/var/benchmarks/gitbug/benchmarks.json"


class IntRange(Iterable[int]):
    def __init__(self, start: int, end: int):
        self.start = start
        self.end = end

    def __contains__(self, num) -> bool:
        return self.start <= num <= self.end

    def __iter__(self):
        return iter([f'[{self.start}..{self.end}]'])


def main():
    parser = argparse.ArgumentParser(description="TGA pipeline executor")
    # general args
    parser.add_argument("--tool", type=Tool, choices=list(Tool), help="Name of tool", required=True)
    parser.add_argument("--runName", type=str, help="Name of the experiment", required=True)
    parser.add_argument("--runs", type=int, choices=IntRange(0, 100_000), help="Number of total runs", required=True)
    parser.add_argument("--timeout", type=int, help="Timeout in seconds", required=True)
    parser.add_argument("--workers", type=int, help="Number of parallel workers", required=True)
    parser.add_argument("--output", type=str, help="Path to folder with output", required=True)

    # kex args
    parser.add_argument("--kexOption", type=str, action='append', nargs='+',
                        help="Additional kex options, optional for kex",
                        required=False)

    # test spark args
    parser.add_argument("--llm", type=str, help="LLM to use, required for TestSpark", required=False)
    parser.add_argument("--llmToken", type=str, help="Grazie token, required for TestSpark", required=False)
    parser.add_argument("--spaceUser", type=str, help="Space user name, required for TestSpark", required=False)
    parser.add_argument("--spaceToken", type=str, help="Space token, required for TestSpark", required=False)
    parser.add_argument("--prompt", type=str, help="LLM prompt for test generation, optional for TestSpark",
                        required=False)

    # EvoSuite args
    parser.add_argument("--evosuiteCliArgs", type=str, nargs='+',
                        help="Additional EvoSuite options for its cli, optional for EvoSuite",
                        required=False)
    parser.add_argument("--evosuiteToolOption", type=str, nargs='+',
                        help="If you want to use already generated LLM test TGA-Tool can handle that if you provide necessary arguments.", required=False)
    args = parser.parse_args()
    print(args)
    print(args.tool)

    tool_args = None
    if args.tool == Tool.kex:
        tool_args = KexArgs(args.kexOption if args.kexOption is not None else [])
    elif args.tool == Tool.EvoSuite:
        tool_args = EvoSuiteArgs(
            evosuite_cli_args=args.evosuiteCliArgs if args.evosuiteCliArgs is not None else [],
            evosuite_tool_option=args.evosuiteToolOption if args.evosuiteToolOption is not None else [])
    elif args.tool == Tool.TestSpark:
        assert args.llm is not None
        assert args.llmToken is not None
        assert args.spaceUser is not None
        assert args.spaceToken is not None
        tool_args = TestSparkArgs(
            model_name=args.llm,
            model_token=args.llmToken,
            space_user=args.spaceUser,
            space_token=args.spaceToken,
            prompt=args.prompt,
        )
    elif args.tool == Tool.Jazzer:
        tool_args = JazzerArgs()
    elif args.tool == Tool.Jazzer:
        tool_args = ManualArgs()
    else:
        print(f'Unknown tool {args.tool}', file=sys.stderr)

    compose_file = generate_compose(args.tool, tool_args,
                                    args.runName, args.runs, args.timeout, args.workers, args.output,
                                    RUNNER_IMAGE, TOOL_IMAGE, BENCHMARKS_FILE)

    tmp = f"{args.runName}.yml" # tempfile.NamedTemporaryFile()
    with open(tmp, 'w') as file:
        print(file.name)
        print(compose_file)
        file.write(str(compose_file))

    confirmation = input("Type 'y' or 'yes' to continue, or 'n' or 'no' to end: ").lower()

    if confirmation in ['n', 'no']:
        print("Stopping the process.")
        return
    elif confirmation in ['y', 'yes']:
        print("Starting docker-compose script.")
    else:
        print("Invalid input. Stopping the process.")
        return

    subprocess.run(['docker-compose', '-f', tmp, 'up'])
    subprocess.run(['docker-compose', '-f', tmp, 'down'])


if __name__ == '__main__':
    main()
