package dev.loam.core.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exclusion is the one setting here that can make a note silently unfindable,
 * so over-matching is the failure to design against — a pattern that quietly
 * eats more of the vault than it says has no symptom other than search coming
 * back empty for something the user knows they wrote.
 */
class ExcludeRulesTest {

    private fun rules(vararg lines: String) = ExcludeRules.parse(lines.joinToString("\n"))

    @Test
    fun aBareNameMatchesThatFileAnywhere() {
        val r = rules("Untitled.md")

        assertTrue(r.excludesFile("Untitled.md"))
        assertTrue(r.excludesFile("linux/deep/nest/Untitled.md"))
        assertFalse(r.excludesFile("linux/Untitled 2.md"))
    }

    @Test
    fun aStarStaysInsideOneSegment() {
        val r = rules("*.excalidraw.md")

        assertTrue(r.excludesFile("drawings/plan.excalidraw.md"))
        assertFalse(r.excludesFile("notes.md"))
    }

    @Test
    fun aPatternWithASlashIsAnchoredToTheVaultRoot() {
        val r = rules("journal/*.md")

        assertTrue(r.excludesFile("journal/2019-01-01.md"))
        // Not anywhere a "journal" folder happens to appear — that is what `**`
        // is for, and conflating the two is how a pattern eats a whole vault.
        assertFalse(r.excludesFile("linux/journal/2019-01-01.md"))
    }

    @Test
    fun doubleStarCrossesSegments() {
        val r = rules("**/archive/**")

        assertTrue(r.excludesFile("linux/archive/old.md"))
        assertTrue(r.excludesFile("a/b/archive/c/d.md"))
        assertFalse(r.excludesFile("linux/archived.md"))
    }

    @Test
    fun aTrailingSlashTakesTheDirectoryAndEverythingUnderIt() {
        val r = rules("templates/")

        assertTrue(r.excludesFile("templates/daily.md"))
        assertTrue(r.excludesFile("templates/nested/weekly.md"))
        assertTrue(r.excludesDirectory("templates"))
        assertFalse(r.excludesFile("linux/templates-guide.md"))
    }

    @Test
    fun aDirectoryIsSkippedRatherThanWalkedAndFiltered() {
        val r = rules("templates/", "journal/2019/")

        // Enumeration is the expensive half of a SAF walk, so this is the
        // difference between not paying for a subtree and paying in full.
        assertTrue(r.excludesDirectory("templates"))
        assertTrue(r.excludesDirectory("journal/2019"))
        assertFalse(r.excludesDirectory("journal"))
        assertFalse(r.excludesDirectory("linux"))
    }

    @Test
    fun regexMetacharactersInPathsStayLiteral() {
        val r = rules("C++ Notes.md", "Step (2).md")

        // A vault really does contain these. Unescaped, "C++" is a regex that
        // matches "C" followed by any number of "+", and the pattern silently
        // becomes a different one.
        assertTrue(r.excludesFile("C++ Notes.md"))
        assertTrue(r.excludesFile("Step (2).md"))
        assertFalse(r.excludesFile("C Notes.md"))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        val r = rules("Templates/")

        // The same vault on a case-insensitive volume must not index a
        // different set of notes.
        assertTrue(r.excludesFile("templates/daily.md"))
        assertTrue(r.excludesDirectory("TEMPLATES"))
    }

    @Test
    fun blanksAndCommentsAreIgnored() {
        val r = rules("", "   ", "# drawings are not prose", "*.excalidraw.md")

        assertTrue(r.excludesFile("plan.excalidraw.md"))
        assertFalse(r.excludesFile("# drawings are not prose"))
    }

    @Test
    fun aLeadingSlashIsAcceptedAndMeansTheSameThing() {
        val r = rules("/journal/*.md")

        // Someone will paste this out of a .gitignore. Matching nothing would
        // look like the feature being broken.
        assertTrue(r.excludesFile("journal/a.md"))
    }

    @Test
    fun noPatternsExcludesNothing() {
        val r = ExcludeRules.parse("\n#only a comment\n  \n")

        assertTrue(r.isEmpty)
        assertFalse(r.excludesFile("anything.md"))
        assertFalse(r.excludesDirectory("anything"))
    }

    @Test
    fun aQuestionMarkDoesNotEatASeparator() {
        val r = rules("a?c.md")

        assertTrue(r.excludesFile("abc.md"))
        assertFalse(r.excludesFile("a/c.md"))
    }
}
