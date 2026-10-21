package me.rerere.document.pdf

import me.rerere.document.PdfParser
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 对本地 PDF 测试集批量提取文字，供与 `mutool draw -F txt` 的输出对比。
 * 需要设置环境变量 PDF_CORPUS_DIR（输入目录）和 PDF_OUT_DIR（输出目录），未设置时跳过。
 */
class PdfCorpusTest {
    @Test
    fun extractCorpus() {
        val corpus = System.getenv("PDF_CORPUS_DIR")?.let(::File)
        val outDir = System.getenv("PDF_OUT_DIR")?.let(::File)
        assumeTrue(corpus != null && corpus.isDirectory && outDir != null)
        outDir!!.mkdirs()
        val report = StringBuilder()
        var executor = newExecutor()
        corpus!!.listFiles { f -> f.extension.equals("pdf", true) }!!.sortedBy { it.name }.forEach { pdf ->
            val start = System.nanoTime()
            val future = executor.submit<String> { PdfParser.parserPdf(pdf) }
            val text = try {
                future.get(30, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                future.cancel(true)
                // 卡住的线程无法回收，换一个新的执行器
                executor = newExecutor()
                "ERROR: timeout"
            } catch (e: Throwable) {
                val cause = e.cause ?: e
                val trace = cause.stackTraceToString().lines().take(12).joinToString("\n")
                "ERROR: ${cause::class.simpleName}: ${cause.message}\n$trace"
            }
            val ms = (System.nanoTime() - start) / 1_000_000
            File(outDir, pdf.nameWithoutExtension + ".txt").writeText(text)
            report.append("${pdf.name}\t${ms}ms\t${text.length}\n")
        }
        File(outDir, "_report.tsv").writeText(report.toString())
    }

    private fun newExecutor(): ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(null, task, "pdf", 64L * 1024 * 1024).also { it.isDaemon = true }
    }
}
