package com.github.vladarts.idegen.config

import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class ConfigParserTest {
    private fun parse(yaml: String) = ConfigParser.parse(StringReader(yaml.trimIndent()))

    @Test fun camelCaseGitLabAuthenticationIsParsed() {
        val entry = parse("""
            gitlab:
              - url: https://gitlab.example.com
                tokenEnvVar: TEST_TOKEN
                tokenCommand: credential-helper
                tokenType: oauth
                includeArchived: true
                httpsUrl: true
                fastForward: true
        """).gitlab.single()
        assertEquals("TEST_TOKEN", entry.tokenEnvVar)
        assertEquals("credential-helper", entry.tokenCommand)
        assertEquals("oauth", entry.tokenType)
        assertTrue(entry.includeArchived)
        assertTrue(entry.httpsUrl)
        assertTrue(entry.fastForward)
    }

    @Test fun snakeCaseTakesPrecedenceWhenBothAliasesArePresent() {
        val entry = parse("""
            gitlab:
              - url: https://gitlab.example.com
                token_env_var: SNAKE_TOKEN
                tokenEnvVar: CAMEL_TOKEN
                token_command: ''
                tokenCommand: credential-helper
                token_type: job
                tokenType: oauth
                include_archived: false
                includeArchived: true
        """).gitlab.single()
        assertEquals("SNAKE_TOKEN", entry.tokenEnvVar)
        assertEquals("", entry.tokenCommand)
        assertEquals("job", entry.tokenType)
        assertFalse(entry.includeArchived)
    }

    @Test fun validSectionsAndDefaultsArePreserved() {
        val config = parse("""
            git:
              - url: git@example.com:org/repo.git
                fast_forward: true
                remotes:
                  upstream: https://example.com/upstream.git
            raw:
              - path: ~/local
            directory:
              - path: ~/another
            gitlab:
              - url: https://gitlab.example.com
                include: ['^org/']
                exclude: ['archive']
        """)
        assertTrue(config.git.single().fastForward)
        assertEquals("https://example.com/upstream.git", config.git.single().remotes["upstream"])
        assertEquals(listOf("~/local", "~/another"), config.raw.map { it.path })
        val entry = config.gitlab.single()
        assertEquals("private", entry.tokenType)
        assertEquals("", entry.tokenEnvVar)
        assertEquals("", entry.tokenCommand)
        assertEquals(listOf("^org/"), entry.include)
        assertEquals(listOf("archive"), entry.exclude)
    }

    @Test fun malformedSectionsAreRejectedInsteadOfBecomingEmpty() {
        for (section in listOf("git", "raw", "directory", "gitlab")) {
            for (value in listOf("{}", "null", "wrong", "[wrong]", "[null]", "[{1: value}]")) {
                assertThrows("$section: $value", IllegalArgumentException::class.java) {
                    parse("$section: $value")
                }
            }
        }
    }

    @Test fun malformedRootAndUnknownSectionsAreRejected() {
        for (yaml in listOf("[]", "wrong", "1: []", "gti: []")) {
            assertThrows(yaml, IllegalArgumentException::class.java) { parse(yaml) }
        }
    }

    @Test fun missingOrInvalidFieldsAreRejectedBeforeReconciliation() {
        val invalid = listOf(
            "git: [{}]", "git: [{url: 123}]", "git: [{url: '  '}]",
            "raw: [{}]", "directory: [{path: false}]", "gitlab: [{}]",
            "git: [{url: valid, fast_forward: 'true'}]",
            "git: [{url: valid, remotes: {upstream: 123}}]",
            "gitlab: [{url: valid, tokenEnvVar: 123}]",
            "gitlab: [{url: valid, token_command: null}]",
            "gitlab: [{url: valid, tokenType: false}]",
            "gitlab: [{url: valid, include: wrong}]",
            "gitlab: [{url: valid, exclude: [123]}]"
        )
        for (yaml in invalid) {
            assertThrows(yaml, IllegalArgumentException::class.java) { parse(yaml) }
        }
    }

    @Test fun intentionallyEmptyConfigIsAllowed() {
        for (yaml in listOf("", "{}", "git: []\nraw: []\ndirectory: []\ngitlab: []")) {
            assertEquals(ProjectGeneratorConfig(), parse(yaml))
        }
    }
}
