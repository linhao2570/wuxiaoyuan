# build.gradle (root)
content1 = '' +
'buildscript {\n' +
'    ext.kotlin_version = ''1.9.0''\n' +
'    repositories {\n' +
'        google()\n' +
'        mavenCentral()\n' +
'    }\n' +
'    dependencies {\n' +
'        classpath ''com.android.tools.build:gradle:8.1.0''\n' +
'        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:"\n' +
'    }\n' +
'}\n' +
'\n' +
'allprojects {\n' +
'    repositories {\n' +
'        google()\n' +
'        mavenCentral()\n' +
'    }\n' +
'}\n' +
'\n' +
'rootProject.buildDir = ''../build''\n' +
'subprojects {\n' +
"    project.buildDir = ${rootProject.buildDir}/\n" +
'}\n' +
'subprojects {\n' +
"    project.evaluationDependsOn('':app'')\n" +
'}\n' +
'\n' +
'tasks.register("clean", Delete) {\n' +
'    delete rootProject.buildDir\n' +
'}\n'

[System.IO.File]::WriteAllText('android\build.gradle', , [System.Text.UTF8Encoding]::new(False))
Write-Output 'done'
