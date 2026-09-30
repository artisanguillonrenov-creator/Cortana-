package io.github.artisanguillonrenov.cortana

import java.io.File

/** Small, realistic fixture projects used by the developer-workspace gates. */
object SampleProjects {
    /** A Kotlin/Gradle calculator with a bug in `add` (returns a - b). */
    fun kotlinCalc(dir: File): File {
        dir.deleteRecursively()
        fun w(path: String, text: String) = File(dir, path).apply { parentFile!!.mkdirs(); writeText(text) }
        w("README.md", "# Calc\nPetite calculatrice. Lancer les tests : ./gradlew test\n")
        w("AGENTS.md", "Ne jamais pousser sur main.\n")
        w("settings.gradle.kts", "rootProject.name = \"calc\"\ninclude(\":app\")\n")
        w("app/build.gradle.kts", "plugins { kotlin(\"jvm\") }\ndependencies {\n    implementation(\"org.jetbrains.kotlin:kotlin-stdlib:2.2.21\")\n    testImplementation(\"junit:junit:4.13.2\")\n}\n")
        w("app/src/main/kotlin/calc/Calc.kt", "package calc\n\nobject Calc {\n    fun add(a: Int, b: Int): Int = a - b // TODO vérifier\n    fun mul(a: Int, b: Int): Int = a * b\n}\n")
        w("app/src/test/kotlin/calc/CalcTest.kt", "package calc\n\nimport org.junit.Assert.assertEquals\nimport org.junit.Test\n\nclass CalcTest {\n    @Test fun add() = assertEquals(5, Calc.add(2, 3))\n    @Test fun mul() = assertEquals(6, Calc.mul(2, 3))\n}\n")
        w(".github/workflows/ci.yml", "name: ci\non: [push]\njobs:\n  test:\n    runs-on: ubuntu-latest\n    steps:\n      - run: ./gradlew test\n")
        w("config/local.properties", "api_key = \"abcd1234efgh5678\"\n")
        w("build/generated.txt", "généré\n")
        File(dir, "logo.bin").writeBytes(byteArrayOf(0, 1, 2, 3, 0, 5))
        return dir
    }

    /** A dependency-free Node project whose `add` is wrong; tests via node:test, build via npm pack. */
    fun jsCalc(dir: File): File {
        dir.deleteRecursively()
        fun w(path: String, text: String) = File(dir, path).apply { parentFile!!.mkdirs(); writeText(text) }
        w("package.json", "{\n  \"name\": \"calc-js\",\n  \"version\": \"1.0.0\",\n  \"main\": \"src/calc.js\",\n  \"files\": [\"src\"],\n  \"scripts\": {\"build\": \"mkdir -p dist && npm pack --pack-destination dist\"}\n}\n")
        w("src/calc.js", "module.exports = {\n  add: (a, b) => a - b,\n  mul: (a, b) => a * b,\n};\n")
        w("test/calc.test.js", "const test = require('node:test');\nconst assert = require('node:assert');\nconst c = require('../src/calc');\n\ntest('add', () => assert.strictEqual(c.add(2, 3), 5));\ntest('mul', () => assert.strictEqual(c.mul(2, 3), 6));\n")
        w("README.md", "# calc-js\n")
        return dir
    }
}
