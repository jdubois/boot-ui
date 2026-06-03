import re
import os
import glob

dto_dir = "bootui-core/src/main/java/io/github/jdubois/bootui/core/dto"
dtos = [f.replace(".java", "") for f in os.listdir(dto_dir) if f.endswith(".java")]

java_files = glob.glob("**/*.java", recursive=True)

for file in java_files:
    with open(file, "r") as f:
        fc = f.read()
    
    modified = False
    lines = fc.split("\n")
    final_lines = []
    
    for line in lines:
        if line.startswith("import io.github.jdubois.bootui.core."):
            # Check if it's importing a DTO
            class_name = line.split(".")[-1].replace(";", "").strip()
            if class_name in dtos:
                # We already added the correct import via io.github.jdubois.bootui.core.dto.X
                # So we can just drop this invalid one
                modified = True
                continue
        final_lines.append(line)
        
    if modified:
        with open(file, "w") as f:
            f.write("\n".join(final_lines))

