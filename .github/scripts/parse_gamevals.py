#!/usr/bin/env python3
"""
Parse Java gameval files from RuneLite GitHub repository and generate gamevals.json
"""
import re
import json
import requests
from pathlib import Path

# Base URL for RuneLite raw files
BASE_URL = "https://raw.githubusercontent.com/runelite/runelite/refs/heads/master/runelite-api/src/main/java/net/runelite/api/gameval"

# Mapping of categories to their Java class files
EXPORT_MAP = {
    'npcs': ['NpcID.java'],
    'objects': ['ObjectID.java', 'ObjectID1.java'],
    'anims': ['AnimationID.java'],
    'spotanims': ['SpotanimID.java']
}

def fetch_java_file(class_name):
    """Fetch a Java file from RuneLite GitHub repository"""
    url = f"{BASE_URL}/{class_name}"
    print(f"Fetching {url}...")
    response = requests.get(url)
    response.raise_for_status()
    return response.text

def parse_java_constants(java_content):
    """
    Parse Java file to extract public static final int constants.
    Returns a dictionary mapping constant names to their values.
    """
    constants = {}
    
    # Pattern to match: public static final int CONSTANT_NAME = VALUE;
    # Also handles multi-line comments before the constant
    pattern = r'(?:/\*\*.*?\*/\s*)?public\s+static\s+final\s+int\s+(\w+)\s*=\s*(-?\d+)\s*;'
    
    matches = re.finditer(pattern, java_content, re.DOTALL | re.MULTILINE)
    
    for match in matches:
        name = match.group(1)
        value = int(match.group(2))
        constants[name] = value
    
    return constants

def main():
    """Main function to fetch, parse, and generate gamevals.json"""
    full_export = {}
    
    for category, file_list in EXPORT_MAP.items():
        print(f"\nProcessing {category}...")
        constants = {}
        
        for java_file in file_list:
            try:
                java_content = fetch_java_file(java_file)
                file_constants = parse_java_constants(java_content)
                constants.update(file_constants)
                print(f"  Found {len(file_constants)} constants in {java_file}")
            except Exception as e:
                print(f"  Error processing {java_file}: {e}")
                raise
        
        full_export[category] = constants
        print(f"  Total {category}: {len(constants)} constants")
    
    # Write output file
    output_path = Path('src/main/resources/rs117/hd/scene/gamevals.json')
    output_path.parent.mkdir(parents=True, exist_ok=True)
    
    json_content = "// AUTO-GENERATED FILE. DO NOT MODIFY.\n" + json.dumps(full_export, indent=4)
    output_path.write_text(json_content, encoding='utf-8')
    
    print(f"\nGenerated {output_path}")
    print(f"Total constants: {sum(len(v) for v in full_export.values())}")

if __name__ == '__main__':
    main()

