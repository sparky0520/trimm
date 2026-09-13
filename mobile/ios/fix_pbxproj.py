import re
import random

with open('Trimm.xcodeproj/project.pbxproj', 'r') as f:
    content = f.read()

# 1. Remove AgentRunDTO.swift
# Find the fileRef for AgentRunDTO.swift
match = re.search(r'([0-9A-F]{24})\s+/\* AgentRunDTO.swift \*/ = {isa = PBXFileReference;', content)
if match:
    file_ref = match.group(1)
    content = re.sub(rf'{file_ref}.*?\n', '', content)
    content = re.sub(rf'[0-9A-F]{{24}} /\* AgentRunDTO.swift in Sources \*/.*?\n', '', content)

# 2. Add ExportCoordinator.swift by copying VideoProcessor.swift pattern
def new_id(): return ''.join(random.choice('0123456789ABCDEF') for _ in range(24))

file_ref = new_id()
build_file = new_id()

# Add PBXBuildFile
content = content.replace(
    '/* VideoProcessor.swift in Sources */ = {isa = PBXBuildFile; fileRef = 71F8E3B27074F4544A8AA54E /* VideoProcessor.swift */; };',
    f'/* VideoProcessor.swift in Sources */ = {{isa = PBXBuildFile; fileRef = 71F8E3B27074F4544A8AA54E /* VideoProcessor.swift */; }};\n\t\t{build_file} /* ExportCoordinator.swift in Sources */ = {{isa = PBXBuildFile; fileRef = {file_ref} /* ExportCoordinator.swift */; }};'
)

# Add PBXFileReference
content = content.replace(
    '/* VideoProcessor.swift */ = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = VideoProcessor.swift; sourceTree = "<group>"; };',
    f'/* VideoProcessor.swift */ = {{isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = VideoProcessor.swift; sourceTree = "<group>"; }};\n\t\t{file_ref} /* ExportCoordinator.swift */ = {{isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = ExportCoordinator.swift; sourceTree = "<group>"; }};'
)

# Add to group
content = content.replace(
    '71F8E3B27074F4544A8AA54E /* VideoProcessor.swift */,',
    f'71F8E3B27074F4544A8AA54E /* VideoProcessor.swift */,\n\t\t\t\t{file_ref} /* ExportCoordinator.swift */,'
)

# Add to Sources build phase
content = content.replace(
    '3B6A143EB5BF63F6D85480CB /* VideoProcessor.swift in Sources */,',
    f'3B6A143EB5BF63F6D85480CB /* VideoProcessor.swift in Sources */,\n\t\t\t\t{build_file} /* ExportCoordinator.swift in Sources */,'
)

with open('Trimm.xcodeproj/project.pbxproj', 'w') as f:
    f.write(content)
print('Done!')
