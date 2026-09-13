require 'xcodeproj'
project_path = '/Users/darsh/trimm/mobile/ios/Trimm.xcodeproj'
project = Xcodeproj::Project.open(project_path)
target = project.targets.first

# Remove AgentRunDTO.swift
file_to_remove = project.files.find { |f| f.path == 'Trimm/Network/AgentRunDTO.swift' || f.path == 'AgentRunDTO.swift' }
if file_to_remove
  file_to_remove.remove_from_project
end

# Add ExportCoordinator.swift
group = project.main_group.find_subpath('Trimm/Media', true)
file_ref = group.new_reference('ExportCoordinator.swift')
target.add_file_references([file_ref])

project.save
puts "Modified pbxproj"
