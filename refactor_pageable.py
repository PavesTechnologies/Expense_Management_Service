import os
import re

def process_file(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    original = content

    # 1. Remove Pageable imports
    content = re.sub(r'import\s+org\.springframework\.data\.domain\.Pageable;\n?', '', content)
    content = re.sub(r'import\s+org\.springframework\.data\.web\.PageableDefault;\n?', '', content)
    
    # Replace Page import with List import if Page was there
    if 'import org.springframework.data.domain.Page;' in content:
        content = content.replace('import org.springframework.data.domain.Page;', '')
        if 'import java.util.List;' not in content:
            # add List import somewhere
            content = re.sub(r'(package [a-z0-9_\.]+;)', r'\1\n\nimport java.util.List;', content, count=1)

    # 2. Replace Page<T> with List<T>
    content = re.sub(r'\bPage<([A-Za-z0-9_]+)>', r'List<\1>', content)

    # 3. Remove @PageableDefault(...) Pageable pageable
    # Can be multiline, but usually one line
    content = re.sub(r'@PageableDefault\([^)]*\)\s*Pageable\s+pageable\s*,?', '', content)
    content = re.sub(r'@PageableDefault\s+Pageable\s+pageable\s*,?', '', content)
    
    # 4. Remove Pageable pageable
    content = re.sub(r',\s*Pageable\s+pageable', '', content)
    content = re.sub(r'Pageable\s+pageable\s*,?\s*', '', content)

    # 5. Remove 'pageable' from method calls
    # like findAll(pageable) -> findAll()
    content = re.sub(r'\(\s*pageable\s*\)', '()', content)
    # like findSomething(..., pageable) -> findSomething(...)
    content = re.sub(r',\s*pageable\s*\)', ')', content)
    content = re.sub(r'\(\s*pageable\s*,', '(', content)

    if content != original:
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(content)
        print(f"Updated: {filepath}")

def main():
    base_dir = r"c:\Users\Sindhu.Yanala\expense-management-service\expense-management-service\src\main\java\com\expense_management_service"
    for root, dirs, files in os.walk(base_dir):
        for file in files:
            if file.endswith(".java"):
                process_file(os.path.join(root, file))

if __name__ == "__main__":
    main()
