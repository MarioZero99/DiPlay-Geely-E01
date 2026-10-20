package com.shilapi.xcertplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the update link makes of the one answer GitHub gives it.
 *
 * The release this app is published through carries two files: the official package and the sums
 * file beside it. Anything else in the release -- and there has been a `-local` package in local
 * runs -- is not something to offer a driver, so the two are matched by name and by nothing looser.
 *
 * The version is the one in the tag, `v<versionName>` where `versionName` is written as `0.4.4-geely`
 * (`scripts/publish-apk.sh` derives both from that one line). The file name carries only the digits,
 * without their dots or their leading zeros, so `V044` from the tag `v0.4.4-geely` -- the file name
 * cannot be turned back into a number on its own, and is not asked to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class UpdateCheckerTest {
    private val checker = UpdateChecker()

    private fun release(tag: String, vararg assets: String): String {
        val list = assets.joinToString(",") { name ->
            """{"name":"$name","browser_download_url":"https://github.com/MarioZero99/DiPlay-Geely-E01/releases/download/$tag/$name","size":6311524}"""
        }
        return """
            {
              "url": "https://api.github.com/repos/MarioZero99/DiPlay-Geely-E01/releases/latest",
              "tag_name": "$tag",
              "name": "DiPlay Geely E01 $tag",
              "body": "Fixed the same-Wi-Fi mode.\n\n- passwords are trimmed",
              "prerelease": false,
              "draft": false,
              "assets": [$list]
            }
        """.trimIndent()
    }

    @Test fun parseReleaseJsonExtractsOfficialApkAndSha256() {
        val info = checker.parseReleaseJson(release("v0.4.5-geely", "DiPlay-Legacy-Geely-V045.apk", "DiPlay-Legacy-Geely-V045-local.apk", "SHA256SUMS.txt"))

        assertNotNull(info)
        info!!
        assertEquals("v0.4.5-geely", info.tagName)
        assertEquals("0.4.5-geely", info.versionName)
        assertEquals(45, info.versionCode)
        assertTrue(info.notes.contains("same-Wi-Fi mode"))
        assertEquals("DiPlay-Legacy-Geely-V045.apk", info.apkAsset.name)
        assertEquals(6_311_524L, info.apkAsset.sizeBytes)
        assertEquals("SHA256SUMS.txt", info.sha256Asset?.name)
    }

    @Test fun parseReleaseJsonReadsAVersionNameThatIsNotThreeParts() {
        val info = checker.parseReleaseJson(release("v0.10.12-geely", "DiPlay-Legacy-Geely-V112.apk"))

        // 0.10.12 is older than 0.11.2, and a comparison on the text would say it was newer: the
        // number keeps a place for each part so that the order is the version's order.
        assertEquals(112, info?.versionCode)
        assertEquals(112, UpdateChecker.versionCodeOf("0.11.2"))
        assertNull("a release without a sums file is still a release", info?.sha256Asset)
    }

    @Test fun parseReleaseJsonRejectsReleaseWithoutOfficialApk() {
        assertNull(checker.parseReleaseJson(release("v0.4.5-geely", "DiPlay-Legacy-Geely-V045-local.apk", "SHA256SUMS.txt")))
    }

    @Test fun parseReleaseJsonRejectsALocalPackageWearingTheOfficialName() {
        // The local package carries the upload key and is not for the public line. The name test is
        // on `local` anywhere in it, because a package can be suffixed rather than prefixed.
        assertNull(checker.parseReleaseJson(release("v0.4.5-geely", "DiPlay-Legacy-Geely-V045-local-build.apk", "SHA256SUMS.txt")))
    }

    @Test fun parseReleaseJsonRejectsATagThatIsNotAVersion() {
        assertNull(checker.parseReleaseJson(release("nightly", "DiPlay-Legacy-Geely-V045.apk", "SHA256SUMS.txt")))
    }

    @Test fun parseReleaseJsonRejectsAMalformedAnswer() {
        assertNull(checker.parseReleaseJson("not json"))
        assertNull(checker.parseReleaseJson(""))
    }

    @Test fun checkResultIdentifiesUpToDateWhenVersionMatchesOrLower() {
        val same = checker.parseReleaseJson(release("v0.4.4-geely", "DiPlay-Legacy-Geely-V044.apk", "SHA256SUMS.txt"))!!
        val older = checker.parseReleaseJson(release("v0.4.3-geely", "DiPlay-Legacy-Geely-V043.apk", "SHA256SUMS.txt"))!!

        assertTrue(checker.compare(same, currentVersionCode = 44) is UpdateChecker.CheckResult.UpToDate)
        assertTrue(checker.compare(older, currentVersionCode = 44) is UpdateChecker.CheckResult.UpToDate)
    }

    @Test fun checkResultOffersANewerRelease() {
        val newer = checker.parseReleaseJson(release("v0.4.5-geely", "DiPlay-Legacy-Geely-V045.apk", "SHA256SUMS.txt"))!!

        val result = checker.compare(newer, currentVersionCode = 44)

        assertTrue(result is UpdateChecker.CheckResult.UpdateFound)
        assertEquals(45, (result as UpdateChecker.CheckResult.UpdateFound).info.versionCode)
    }

    @Test fun theSumsFileIsOfferedFromWhereTheReleasePutIt() {
        val info = checker.parseReleaseJson(release("v0.4.5-geely", "DiPlay-Legacy-Geely-V045.apk", "SHA256SUMS.txt"))!!

        assertTrue(info.sha256Asset!!.downloadUrl.endsWith("/releases/download/v0.4.5-geely/SHA256SUMS.txt"))
        assertTrue(info.apkAsset.downloadUrl.endsWith("/releases/download/v0.4.5-geely/DiPlay-Legacy-Geely-V045.apk"))
    }
}
