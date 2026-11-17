package io.github.escosis.hwtfaceswap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    @Test
    fun 补丁版本按数字比较而非字符串截断() {
        assertTrue(UpdateChecker.isNewer("1.0.10", "1.0.1"))
        assertTrue(UpdateChecker.isNewer("v1.0.10", "v1.0.9"))
        assertFalse(UpdateChecker.isNewer("1.0.1", "1.0.10"))
    }

    @Test
    fun 版本前缀不影响相同版本判断() {
        assertFalse(UpdateChecker.isNewer("v1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("1.0.0", "v1.0.0"))
        assertFalse(UpdateChecker.isNewer("V1.0.0", "v1.0.0"))
        assertEquals("1.0.10", UpdateVersion.parse("V1.0.10")?.version)
    }

    @Test
    fun 主版本和次版本优先于后续位数() {
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.99.99"))
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.99"))
        assertFalse(UpdateChecker.isNewer("1.99.99", "2.0.0"))
        assertFalse(UpdateChecker.isNewer("1.9.99", "1.10.0"))
    }

    @Test
    fun 零版本可解析且必须严格较新() {
        assertNotNull(UpdateVersion.parse("0.0.0"))
        assertFalse(UpdateChecker.isNewer("0.0.0", "0.0.0"))
        assertTrue(UpdateChecker.isNewer("0.0.1", "0.0.0"))
    }

    @Test
    fun 远端非法版本不能产生更新提示() {
        for (value in invalidVersions) {
            assertNull("应拒绝非法版本：$value", UpdateVersion.parse(value))
            assertFalse("不能提示非法远端版本：$value", UpdateChecker.isNewer(value, "1.0.0"))
        }
    }

    @Test
    fun 当前版本非法时不猜测比较结果() {
        for (value in invalidVersions) {
            assertFalse("不能猜测当前版本：$value", UpdateChecker.isNewer("2.0.0", value))
        }
    }

    @Test
    fun 整数边界比较不会溢出() {
        assertNotNull(UpdateVersion.parse("2147483647.2147483647.2147483647"))
        assertTrue(UpdateChecker.isNewer("2147483647.0.0", "0.0.0"))
        assertFalse(UpdateChecker.isNewer("0.0.0", "2147483647.0.0"))
        assertNull(UpdateVersion.parse("2147483648.0.0"))
        assertNull(UpdateVersion.parse("0.2147483648.0"))
        assertNull(UpdateVersion.parse("0.0.2147483648"))
    }

    @Test
    fun 超长版本输入被限长拒绝() {
        assertNull(UpdateVersion.parse("9".repeat(100_000) + ".0.0"))
        assertFalse(UpdateChecker.isNewer("v".repeat(100_000) + "1.0.1", "1.0.0"))
    }

    private val invalidVersions = listOf(
        "", "1", "1.0", "1.0.0.1", "1.0.0-beta", "v1.0.0-rc.1", "1.0.0+7",
        "-1.0.0", "1.-1.0", "1.0.-1", "+1.0.0", "1.0.0 ", " 1.0.0", "1.0.0\n",
        "01.0.0", "1.00.0", "1.0.00", "vv1.0.0", "１.０.０", "1.0e0.1", "1.0.x",
    )
}
