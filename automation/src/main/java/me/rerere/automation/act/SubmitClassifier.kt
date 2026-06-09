package me.rerere.automation.act

import me.rerere.automation.observe.UiTarget

/**
 * Submit-class classifier for the general tap (#198 slice 11, design Q2 / I-act-5). Decides whether a
 * tap on a resolved [UiTarget] is "submit-class" — a commit/send/pay-style action whose effect is
 * irreversible and side-effect-committing — and therefore carries the dangerous [me.rerere.automation
 * .cap.Sink.SUBMIT] (which the core then gates behind an out-of-band user confirmation).
 *
 * This classifier is INTENTIONALLY OVER-BROAD (conservative by construction), and must NOT be
 * weakened to reduce false positives. The asymmetry is the whole point:
 *  - a FALSE POSITIVE costs exactly one harmless extra confirmation prompt on a benign tap;
 *  - a FALSE NEGATIVE lets the model commit an UNCONFIRMED irreversible pay/transfer — catastrophic.
 * So when in doubt, classify as submit. It matches a broad, MULTILINGUAL commit-keyword set as a
 * case-insensitive SUBSTRING of any of three inputs: the visible [UiTarget.text], the
 * [UiTarget.semanticKey] (content-description), and the raw [UiTarget.viewId] (resource id). The view
 * id is load-bearing for ICON-ONLY commit buttons (no text, no content-description, but an id like
 * `…:id/pay_button`) — and, since resource ids are developer-named (typically English) even in a
 * localized app, it catches a localized pay button whose visible text is non-English.
 *
 * Multilingual by necessity: rikkahub ships en/zh/zh-rTW/ja/ko/ru, and an unconfirmed pay in a
 * non-English app is the exact catastrophic case. An English-only keyword set silently misses
 * `确认支付` / `決済` / `결제` / `оплатить` (the codex-found false-negative). Keep the localized terms.
 *
 * Plain affirmatives ("OK", "Cancel", "Back", "Close", "Edit", "Search") are NOT in the set, so an
 * ordinary dialog tap (the slice-10 "OK"-button happy path) stays non-submit and is never gated. Bare
 * wizard verbs ("Continue"/"Next"/"Proceed") are deliberately EXCLUDED — they are so common that
 * including them would fire a prompt on nearly every step, and confirm-fatigue (reflexive click-through)
 * defeats the gate; only payment-context phrases ("continue to pay", "proceed to checkout") are in.
 * Only a TAP is ever classified; scroll / global-nav / set_text are never submit-class (the core only
 * consults this for [me.rerere.automation.backend.NodeActionKind.CLICK]).
 *
 * Total & pure (module purity I10 — no android.*): null inputs are treated as no-match, never throws;
 * the same target always yields the same result.
 */
object SubmitClassifier {

    /**
     * The commit-keyword set, lowercased. Deliberately broad and multilingual: every term that
     * plausibly labels a commit/send/pay/checkout/subscribe action in a supported (or common) locale.
     * Multi-word and CJK entries match as a SUBSTRING just like single words, so a localized or
     * differently-spaced label still trips. Adding a term here only ever ADDS confirmation prompts —
     * never removes a safety gate — so err toward inclusion (design I-act-5: over-broad on purpose).
     */
    val COMMIT_KEYWORDS: List<String> = listOf(
        // English (also matches developer-named resource ids like id/pay_button, id/submit_order)
        "submit", "send money", "send", "pay", "buy", "purchase", "place order", "order",
        "checkout", "check out", "confirm", "transfer", "subscribe", "donate",
        "continue to pay", "proceed to pay", "proceed to checkout",
        "complete purchase", "complete order",
        // Chinese (simplified + traditional)
        "支付", "付款", "购买", "購買", "下单", "下單", "确认", "確認", "转账", "轉賬", "轉帳",
        "订阅", "訂閱", "捐", "提交", "发送", "發送", "结账", "結賬", "結帳", "购物车",
        // Japanese
        "支払", "購入", "注文", "送信", "送金", "購読", "寄付", "決済", "申し込",
        // Korean
        "결제", "구매", "주문", "확인", "송금", "구독", "기부", "전송", "제출",
        // Russian (lowercased stems — substring catches inflections)
        "оплат", "куп", "заказ", "подтверд", "перевод", "подпис", "пожертв", "отправ",
        // Other common European (conservative — payment-specific stems only)
        "kauf", "bezahl", "bestell", "abonnier", "spende", "bestätig", // German
        "pagar", "comprar", "suscrib", // Spanish
        "payer", "achet", "abonn", // French
    )

    /**
     * True iff [target] is a submit-class tap target — ANY [COMMIT_KEYWORDS] entry appears as a
     * case-insensitive substring of the target's [UiTarget.text], [UiTarget.semanticKey], OR
     * [UiTarget.viewId]. Total: a null field contributes no match (never throws).
     */
    fun isSubmitClass(target: UiTarget): Boolean =
        isSubmitClass(target.text, target.semanticKey, target.viewId)

    /**
     * Field-level overload (the load-bearing predicate): true iff any commit keyword is a
     * case-insensitive substring of [text], [semanticKey], OR [viewId]. Any field may be null (treated
     * as no-match). Each field is lowercased once and scanned independently, so a keyword can only
     * match WITHIN a single field (never spanning a field boundary).
     */
    fun isSubmitClass(text: String?, semanticKey: String?, viewId: String? = null): Boolean {
        val fields = listOfNotNull(text?.lowercase(), semanticKey?.lowercase(), viewId?.lowercase())
        return COMMIT_KEYWORDS.any { keyword -> fields.any { it.contains(keyword) } }
    }
}
