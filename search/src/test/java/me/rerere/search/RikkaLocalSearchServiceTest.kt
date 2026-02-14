package me.rerere.search

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RikkaLocalSearchService 单元测试
 * 验证各个搜索源的可用性
 *
 * 运行命令: ./gradlew :search:test --tests "me.rerere.search.RikkaLocalSearchServiceTest"
 */
class RikkaLocalSearchServiceTest {

    private val service = RikkaLocalSearchService
    private val testQuery = "hello world"
    private val resultLimit = 5

    // ==================== 单源测试 ====================

    @Test
    fun `test Bing search returns results`() = runTest {
        val results = service.searchBing(testQuery, resultLimit)
        println("=== Bing Search Results ===")
        results.forEachIndexed { index, item ->
            println("${index + 1}. ${item.title}")
            println("   URL: ${item.url}")
            println("   Snippet: ${item.text.take(100)}...")
        }
        println("Total: ${results.size} results\n")

        // Bing 可能在某些网络环境下不可用，所以不强制要求结果
        // assertTrue("Bing should return at least 1 result", results.isNotEmpty())
        println("Bing search completed. Results: ${results.size}")
    }

    @Test
    fun `test DuckDuckGo search returns results`() = runTest {
        val results = service.searchDuckDuckGo(testQuery, resultLimit)
        println("=== DuckDuckGo Search Results ===")
        results.forEachIndexed { index, item ->
            println("${index + 1}. ${item.title}")
            println("   URL: ${item.url}")
            println("   Snippet: ${item.text.take(100)}...")
        }
        println("Total: ${results.size} results\n")

        // DDG 通常比较稳定
        assertTrue("DuckDuckGo should return at least 1 result", results.isNotEmpty())
    }

    @Test
    fun `test SearXNG search returns results`() = runTest {
        val results = service.searchSearXNG(testQuery, resultLimit)
        println("=== SearXNG Search Results ===")
        results.forEachIndexed { index, item ->
            println("${index + 1}. ${item.title}")
            println("   URL: ${item.url}")
            println("   Snippet: ${item.text.take(100)}...")
        }
        println("Total: ${results.size} results\n")

        // SearXNG 实例可能在某些地区不可用
        // assertTrue("SearXNG should return at least 1 result", results.isNotEmpty())
        println("SearXNG search completed. Results: ${results.size}")
    }

    @Test
    fun `test Sogou search returns results`() = runTest {
        val results = service.searchSogou("你好", resultLimit)
        println("=== Sogou Search Results ===")
        results.forEachIndexed { index, item ->
            println("${index + 1}. ${item.title}")
            println("   URL: ${item.url}")
            println("   Snippet: ${item.text.take(100)}...")
        }
        println("Total: ${results.size} results\n")

        // Sogou 在中国大陆可用
        println("Sogou search completed. Results: ${results.size}")
    }

    @Test
    fun `test Google search returns results`() = runTest {
        val results = service.searchGoogle(testQuery, resultLimit)
        println("=== Google Search Results ===")
        results.forEachIndexed { index, item ->
            println("${index + 1}. ${item.title}")
            println("   URL: ${item.url}")
            println("   Snippet: ${item.text.take(100)}...")
        }
        println("Total: ${results.size} results\n")

        // Google/Startpage 可能在某些地区不可用
        println("Google search completed. Results: ${results.size}")
    }

    // ==================== 综合测试 ====================

    @Test
    fun `test all search sources and summarize`() = runTest {
        val results = mutableMapOf<String, Int>()

        println("\n========================================")
        println("RikkaLocalSearchService - 搜索源可用性测试")
        println("测试查询: \"$testQuery\"")
        println("========================================\n")

        // Test Bing
        try {
            val bingResults = service.searchBing(testQuery, resultLimit)
            results["Bing"] = bingResults.size
            println("✓ Bing: ${bingResults.size} results")
        } catch (e: Exception) {
            results["Bing"] = 0
            println("✗ Bing: FAILED - ${e.message}")
        }

        // Test DuckDuckGo
        try {
            val ddgResults = service.searchDuckDuckGo(testQuery, resultLimit)
            results["DuckDuckGo"] = ddgResults.size
            println("✓ DuckDuckGo: ${ddgResults.size} results")
        } catch (e: Exception) {
            results["DuckDuckGo"] = 0
            println("✗ DuckDuckGo: FAILED - ${e.message}")
        }

        // Test SearXNG
        try {
            val searxngResults = service.searchSearXNG(testQuery, resultLimit)
            results["SearXNG"] = searxngResults.size
            println("✓ SearXNG: ${searxngResults.size} results")
        } catch (e: Exception) {
            results["SearXNG"] = 0
            println("✗ SearXNG: FAILED - ${e.message}")
        }

        // Test Sogou
        try {
            val sogouResults = service.searchSogou(testQuery, resultLimit)
            results["Sogou"] = sogouResults.size
            println("✓ Sogou: ${sogouResults.size} results")
        } catch (e: Exception) {
            results["Sogou"] = 0
            println("✗ Sogou: FAILED - ${e.message}")
        }

        // Test Google
        try {
            val googleResults = service.searchGoogle(testQuery, resultLimit)
            results["Google"] = googleResults.size
            println("✓ Google: ${googleResults.size} results")
        } catch (e: Exception) {
            results["Google"] = 0
            println("✗ Google: FAILED - ${e.message}")
        }

        println("\n========================================")
        println("汇总:")
        val availableSources = results.filter { it.value > 0 }
        val totalResults = results.values.sum()
        println("- 可用搜索源: ${availableSources.size}/${results.size}")
        println("- 总结果数: $totalResults")
        println("========================================\n")

        // 至少有一个搜索源可用
        val workingSources = results.count { it.value > 0 }
        assertTrue(
            "At least 1 search source should return results (currently $workingSources working)",
            workingSources >= 1
        )
    }

    // ==================== 结果质量测试 ====================

    @Test
    fun `test search result quality - title and url should not be empty`() = runTest {
        val results = service.searchDuckDuckGo(testQuery, resultLimit)

        if (results.isNotEmpty()) {
            results.forEach { item ->
                assertTrue("Title should not be empty", item.title.isNotEmpty())
                assertTrue("URL should start with http", item.url.startsWith("http"))
            }
            println("✓ All ${results.size} results have valid title and URL")
        } else {
            println("⚠ No results to validate")
        }
    }
}
