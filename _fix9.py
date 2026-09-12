import os

path = '.github/workflows/build.yml'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

old = """      - name: Create platform projects
        run: flutter create . --platforms android --org com.wyu.esurfing"""

new = """      - name: Create android platform project
        run: |
          rm -rf android
          flutter create . --platforms android --org com.wyu --project-name esurfing
"""

content = content.replace(old, new)

with open(path, 'w', encoding='utf-8', newline='\n') as f:
    f.write(content)

print('workflow updated: will regenerate full android dir')
