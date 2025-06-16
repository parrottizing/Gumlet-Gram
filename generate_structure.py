import os
import json
from collections import Counter

INCLUDE_EXTENSIONS = {'.java'}
EXCLUDE_PATHS = {
    os.path.normpath("TMessagesProj/.cxx"),
    os.path.normpath("TMessagesProj/src/main/res"),
    os.path.normpath("TMessagesProj/src/main/assests"),
    os.path.normpath("TMessagesProj/jni"),
    os.path.normpath("TMessagesProj_AppHockeyApp"),
    os.path.normpath("TMessagesProj_AppHuawei"),
    os.path.normpath("TMessagesProj_AppStandalone"),
    os.path.normpath("TMessagesProj_App"),
}

SUMMARY = {
    "total_files": 0,
    "by_extension": Counter(),
    "max_depth": 0,
}

def is_excluded(rel_path):
    rel_path_norm = rel_path.replace("\\", "/")
    for exclude in EXCLUDE_PATHS:
        exclude_norm = exclude.replace("\\", "/")
        if rel_path_norm == exclude_norm or rel_path_norm.startswith(exclude_norm + "/"):
            return True
    return False

def build_filtered_tree(root_path, max_depth=8):
    tree = {}

    def add_to_tree(path, current_dict, depth):
        if depth > max_depth:
            return
        parts = path.split(os.sep)
        for part in parts[:-1]:
            current_dict = current_dict.setdefault(part, {})
        current_dict[parts[-1]] = None

    for root, dirs, files in os.walk(root_path):
        rel_root = os.path.relpath(root, root_path)
        if is_excluded(rel_root):
            dirs[:] = []
            continue

        depth = rel_root.count(os.sep)
        SUMMARY["max_depth"] = max(SUMMARY["max_depth"], depth)

        if depth >= max_depth:
            continue

        for file in files:
            ext = os.path.splitext(file)[1]
            if ext in INCLUDE_EXTENSIONS:
                rel_file_path = os.path.join(rel_root, file).replace("\\", "/")
                add_to_tree(rel_file_path, tree, depth)
                SUMMARY["total_files"] += 1
                SUMMARY["by_extension"][ext] += 1

    return tree

if __name__ == "__main__":
    root_dir = "."
    tree = build_filtered_tree(root_dir)

    output_data = {
        "structure": tree,
        "summary": {
            "total_files": SUMMARY["total_files"],
            "by_extension": dict(SUMMARY["by_extension"]),
            "max_depth": SUMMARY["max_depth"]
        }
    }

    with open("java_only.json", "w", encoding="utf-8") as f:
        json.dump(output_data, f, indent=2, ensure_ascii=False)

    print("✅ Структура только по .java сохранена в 'java_only.json'")