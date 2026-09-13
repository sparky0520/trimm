require 'xcodeproj'
project_path = 'Trimm.xcodeproj'
project = Xcodeproj::Project.open(project_path)
target = project.targets.find { |t| t.name == 'Trimm' }

files = [
  'Trimm/Media/ExportWorker.swift'
]

media_group = project.main_group.find_subpath('Trimm/Media', true)

files.each do |file|
  next if media_group.files.any? { |f| f.path == file.split('/').last }
  file_ref = media_group.new_reference(file.split('/').last)
  target.add_file_references([file_ref])
end

project.save
