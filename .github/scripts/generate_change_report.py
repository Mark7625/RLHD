#!/usr/bin/env python3
"""
Compare old and new gamevals.json files and generate a change report.
Detects renamed (same ID, different name), added, and removed gamevals.
"""
import json
import sys
from pathlib import Path
import subprocess

def load_gamevals(file_path):
    """Load gamevals.json file"""
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()
        # Remove the comment line if present
        if content.startswith('//'):
            content = '\n'.join(content.split('\n')[1:])
        return json.loads(content)

def reverse_map(gamevals):
    """Create reverse mapping: ID -> name for each category"""
    reverse = {}
    for category, constants in gamevals.items():
        reverse[category] = {}
        for name, id_value in constants.items():
            if id_value not in reverse[category]:
                reverse[category][id_value] = []
            reverse[category][id_value].append(name)
    return reverse

def compare_gamevals(old_gamevals, new_gamevals):
    """Compare old and new gamevals and detect changes"""
    changes = {
        'renamed': {},  # category -> [(old_name, new_name, id)]
        'added': {},    # category -> [(name, id)]
        'removed': {}   # category -> [(name, id)]
    }
    
    # Check all categories
    all_categories = set(old_gamevals.keys()) | set(new_gamevals.keys())
    
    for category in all_categories:
        old_constants = old_gamevals.get(category, {})
        new_constants = new_gamevals.get(category, {})
        
        # Build ID -> name mappings
        old_id_to_names = {}
        for name, id_val in old_constants.items():
            if id_val not in old_id_to_names:
                old_id_to_names[id_val] = []
            old_id_to_names[id_val].append(name)
        
        new_id_to_names = {}
        for name, id_val in new_constants.items():
            if id_val not in new_id_to_names:
                new_id_to_names[id_val] = []
            new_id_to_names[id_val].append(name)
        
        # Track which IDs we've already processed
        processed_ids = set()
        
        # Find renames: IDs that exist in both but with different names
        for id_val in set(old_id_to_names.keys()) & set(new_id_to_names.keys()):
            old_names = set(old_id_to_names[id_val])
            new_names = set(new_id_to_names[id_val])
            
            if old_names != new_names:
                # Some names changed - this is a rename
                processed_ids.add(id_val)
                if category not in changes['renamed']:
                    changes['renamed'][category] = []
                # Report each old name that doesn't exist in new
                for old_name in old_names - new_names:
                    for new_name in new_names - old_names:
                        changes['renamed'][category].append((old_name, new_name, id_val))
        
        # Find removed: IDs that exist in old but not in new (and weren't renamed)
        for id_val in set(old_id_to_names.keys()) - set(new_id_to_names.keys()):
            if id_val not in processed_ids:
                if category not in changes['removed']:
                    changes['removed'][category] = []
                for old_name in old_id_to_names[id_val]:
                    changes['removed'][category].append((old_name, id_val))
        
        # Find added: IDs that exist in new but not in old
        for id_val in set(new_id_to_names.keys()) - set(old_id_to_names.keys()):
            if category not in changes['added']:
                changes['added'][category] = []
            for new_name in new_id_to_names[id_val]:
                changes['added'][category].append((new_name, id_val))
        
        # Also check for names that changed ID (should be rare but handle it)
        for old_name, old_id in old_constants.items():
            if old_name in new_constants:
                new_id = new_constants[old_name]
                if old_id != new_id:
                    # Name exists but ID changed - treat as removal + addition
                    if category not in changes['removed']:
                        changes['removed'][category] = []
                    changes['removed'][category].append((old_name, old_id))
                    if category not in changes['added']:
                        changes['added'][category] = []
                    changes['added'][category].append((old_name, new_id))
    
    return changes

def generate_report(changes):
    """Generate a markdown report of changes"""
    report_lines = []
    
    has_warnings = False
    if any(changes['renamed'].values()) or any(changes['removed'].values()):
        has_warnings = True
        report_lines.append("## ⚠️ WARNING: FOLLOWING CHANGES HAVE BEEN MADE")
        report_lines.append("")
    
    # Report renamed gamevals
    if any(changes['renamed'].values()):
        report_lines.append("### 🔄 Renamed Gamevals")
        report_lines.append("")
        for category, renamed_list in changes['renamed'].items():
            if renamed_list:
                report_lines.append(f"**{category.upper()}:**")
                for old_name, new_name, id_val in renamed_list:
                    report_lines.append(f"- `{old_name}` → `{new_name}` (ID: {id_val})")
                report_lines.append("")
    
    # Report removed gamevals
    if any(changes['removed'].values()):
        report_lines.append("### ❌ Removed Gamevals")
        report_lines.append("")
        for category, removed_list in changes['removed'].items():
            if removed_list:
                report_lines.append(f"**{category.upper()}:**")
                for name, id_val in removed_list:
                    report_lines.append(f"- `{name}` (ID: {id_val})")
                report_lines.append("")
    
    # Report added gamevals
    if any(changes['added'].values()):
        report_lines.append("### ✅ Added Gamevals")
        report_lines.append("")
        for category, added_list in changes['added'].items():
            if added_list:
                report_lines.append(f"**{category.upper()}:** ({len(added_list)} new)")
                # Show first 20, then summarize if more
                display_list = added_list[:20]
                for name, id_val in display_list:
                    report_lines.append(f"- `{name}` (ID: {id_val})")
                if len(added_list) > 20:
                    report_lines.append(f"- ... and {len(added_list) - 20} more")
                report_lines.append("")
    
    if not has_warnings and not any(changes['added'].values()):
        report_lines.append("No changes detected.")
    
    return '\n'.join(report_lines)

def main():
    """Main function"""
    # Get the old version from git
    try:
        result = subprocess.run(
            ['git', 'show', 'HEAD:src/main/resources/rs117/hd/scene/gamevals.json'],
            capture_output=True,
            text=True,
            check=True
        )
        old_content = result.stdout
        if old_content.startswith('//'):
            old_content = '\n'.join(old_content.split('\n')[1:])
        old_gamevals = json.loads(old_content)
    except subprocess.CalledProcessError:
        print("Warning: Could not fetch old gamevals.json from git", file=sys.stderr)
        old_gamevals = {}
    
    # Load new version
    new_path = Path('src/main/resources/rs117/hd/scene/gamevals.json')
    if not new_path.exists():
        print("Error: New gamevals.json not found", file=sys.stderr)
        sys.exit(1)
    
    new_gamevals = load_gamevals(new_path)
    
    # Compare
    changes = compare_gamevals(old_gamevals, new_gamevals)
    
    # Generate report
    report = generate_report(changes)
    print(report)

if __name__ == '__main__':
    main()

