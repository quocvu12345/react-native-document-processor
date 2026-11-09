require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name = package["name"]
  s.version = package["version"]
  s.summary = package["description"]
  s.homepage = package["homepage"]
  s.license = package["license"]
  s.author = package["author"]
  s.source = { :git => "https://github.com/quocvu12345/react-native-document-processor.git",
               :tag => "v#{s.version}" }
  s.platforms = { :ios => "15.1" }
  s.source_files = "ios/**/*.{h,m}"
  s.frameworks = "Vision", "VisionKit", "ImageIO", "UIKit", "CoreGraphics"
  s.dependency "React-Core"
end
