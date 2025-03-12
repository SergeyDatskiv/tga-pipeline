import argparse
import json
import csv
import logging


def filter_json_by_csv(json_file_path, csv_file_path, csv_parameter_column, json_parameter_name):
    """
    Filters a JSON file based on values in a CSV file.

    Args:
        :param json_file_path: Path to the JSON file.
        :param csv_file_path: Path to the CSV file.
        :param csv_parameter_column: The name of the column in the CSV file to use for filtering.  This column's values should match the field in the JSON entries.
        :param json_parameter_name: Name of the JSON parameter to use for filtering

    """

    logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')

    try:
        with open(json_file_path, 'r') as f:
            data = json.load(f)
    except FileNotFoundError:
        logging.error(f"Error: JSON file not found at {json_file_path}")
        return
    except json.JSONDecodeError:
        logging.error(f"Error: Invalid JSON format in {json_file_path}")
        return

    try:
        with open(csv_file_path, 'r', newline='') as csvfile:
            reader = csv.DictReader(csvfile)
            csv_values = {row[csv_parameter_column] for row in reader}
    except FileNotFoundError:
        logging.error(f"Error: CSV file not found at {csv_file_path}")
        return
    except KeyError:
        logging.error(f"Error: Column '{csv_parameter_column}' not found in CSV file.")
        return

    filtered_data = []
    for entry in data:
        if json_parameter_name in entry and entry[json_parameter_name] in csv_values:
            filtered_data.append(entry)
        else:
            logging.info(f"Removed entry: {entry}")
    logging.info(f"Total number of entries in JSON: {len(data)}")
    logging.info(f"Removed entries count: {len(data) - len(filtered_data)}")
    logging.info(f"Filtered entries count: {len(filtered_data)}")
    assert len(filtered_data) == len(csv_values), logging.info(f"EvoSuite benchmarks count mismatch after filtering: {len(filtered_data)} != {len(csv_values)}")
    try:
        with open(json_file_path, 'w') as f:
            json.dump(filtered_data, f, indent=4)
        logging.info(f"Updated JSON file: {json_file_path}")
    except Exception as e:
        logging.error(f"An error occurred while writing to the JSON file: {e}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Filter a JSON file based on a CSV file.")
    parser.add_argument("json_file", help="Path to the JSON file")
    parser.add_argument("csv_file", help="Path to the CSV file")
    parser.add_argument("csv_column", help="Name of the column in the CSV file to use for filtering")
    parser.add_argument("json_parameter", help="Name of the JSON parameter to use for filtering")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')

    filter_json_by_csv(args.json_file, args.csv_file, args.csv_column, args.json_parameter)
