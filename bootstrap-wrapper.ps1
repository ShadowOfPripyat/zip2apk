$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path "gradle/wrapper" | Out-Null
$jar = "gradle/wrapper/gradle-wrapper.jar"
Invoke-WebRequest "https://services.gradle.org/distributions/gradle-9.6.0-wrapper.jar" -OutFile $jar
$expected = (Invoke-WebRequest "https://services.gradle.org/distributions/gradle-9.6.0-wrapper.jar.sha256").Content.Trim()
$actual = (Get-FileHash $jar -Algorithm SHA256).Hash.ToLower()
if ($actual -ne $expected.ToLower()) { throw "Gradle wrapper checksum mismatch" }
Write-Host "Gradle wrapper ready."
