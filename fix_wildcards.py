import re
import os
import glob

dto_dir = "bootui-core/src/main/java/io/github/jdubois/bootui/core/dto"
dtos = set([f.replace(".java", "") for f in os.listdir(dto_dir) if f.endswith(".java")])

java_files = glob.glob("**/*.java", recursive=True)

for file in java_files:
    if "BootUiDtos.java" in file or file.startswith("bootui-core/src/main/java/io/github/jdubois/bootui/core/dto/"):
        continue
        
    with open(file, "r") as f:
        fc = f.read()
        
    original_fc = fc
    
    needs_wildcard_fix = False
    
    if re.search(r'import io\.github\.jdubois\.bootui\.core\.BootUiDtos\.\*;\n?', fc):
        needs_wildcard_fix = True
        fc = re.sub(r'import io\.github\.jdubois\.bootui\.core\.BootUiDtos\.\*;\n?', '', fc)
        
    if re.search(r'import static io\.github\.jdubois\.bootui\.core\.BootUiDtos\.\*;\n?', fc):
        needs_wildcard_fix = True
        fc = re.sub(r'import static io\.github\.jdubois\.bootui\.core\.BootUiDtos\.\*;\n?', '', fc)
        
    if needs_wildcard_fix:
        # scan for any dto usage
        used = set()
        for dto in dtos:
            if re.search(r'\b' + dto + r'\b', fc):
                used.add(dto)
                
        new_imports = []
        for dto in sorted(used):
            imp = f"import io.github.jdubois.bootui.core.dto.{dto};"
            if imp not in fc:
                new_imports.append(imp)
                
        if new_imports:
            lines = fc.split("\n")
            out_lines = []
            inserted = False
            last_import = -1
            for i, line in enumerate(lines):
                if line.startswith("import "):
                    last_import = i
                    
            if last_import != -1:
                out_lines = lines[:last_import+1] + new_imports + lines[last_import+1:]
            else:
                for i, line in enumerate(lines):
                    out_lines.append(line)
                    if line.startswith("package ") and not inserted:
                        out_lines.append("")
                        out_lines.extend(new_imports)
                        inserted = True
            fc = "\n".join(out_lines)
            
    if fc != original_fc:
        with open(file, "w") as f:
            f.write(fc)

