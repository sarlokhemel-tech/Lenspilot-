package com.hemel.lenspilot.keyboard

/**
 * Starter word list for LenspilotInputMethodService's suggestion bar.
 *
 * This is a small, hand-picked set of very high-frequency Bangla words
 * (pronouns, common verbs/verb-forms, connectors, everyday nouns) — enough
 * for the suggestion strip to genuinely work for common typing, matching
 * the reference keyboard's UI/behaviour. It is NOT a full dictionary: a
 * production keyboard would ship a proper frequency-ranked word list
 * (tens of thousands of entries) as a bundled asset file and load it once
 * instead of a hard-coded array. Swap WORDS below for an asset-backed
 * list later without touching any of the suggestion-bar wiring in
 * LenspilotInputMethodService (updateSuggestions/applySuggestion already
 * only depend on this object exposing `WORDS: List<String>`).
 *
 * Ordered roughly by frequency — updateSuggestions() takes the first 3
 * prefix matches in this order, so more common words should stay earlier.
 */
object CommonBanglaWords {
    val WORDS: List<String> = listOf(
        // pronouns / person
        "আমি", "আমার", "আমাকে", "আমরা", "আমাদের",
        "তুমি", "তোমার", "তোমাকে", "তোমরা", "তোমাদের",
        "আপনি", "আপনার", "আপনাকে", "আপনারা",
        "সে", "তার", "তাকে", "তারা", "তাদের", "ও", "ওরা",
        "এই", "ওই", "সেই", "কি", "কী", "কে", "কেন", "কেমন", "কোথায়", "কখন", "কিভাবে", "কিভাবে",

        // very common verbs / verb-forms
        "করি", "করো", "করেন", "করব", "করবো", "করছি", "করছে", "করেছি", "করেছে", "করেছিলাম",
        "হয়", "হবে", "হচ্ছে", "হয়েছে", "হয়েছিল", "ছিল", "ছিলাম", "ছিলেন",
        "আছে", "আছি", "আছেন", "নেই", "নাই",
        "যাব", "যাবো", "যাচ্ছি", "যাচ্ছে", "যায়", "গেছে", "গিয়েছিলাম",
        "আসি", "আসছি", "আসবে", "এসেছি",
        "খাব", "খাচ্ছি", "খেয়েছি",
        "দেখি", "দেখছি", "দেখেছি", "দেখবো",
        "বলি", "বললাম", "বলছি", "বলেছি", "বলবো",
        "চাই", "চাও", "চায়", "চেয়েছিলাম",
        "পারি", "পারবো", "পারছি না", "পারবে",
        "জানি", "জানো", "জানেন", "জানি না",
        "বুঝি", "বুঝেছি", "বুঝতে",
        "লাগবে", "লাগছে", "দিব", "দিচ্ছি", "দিয়েছি", "নিব", "নিচ্ছি", "নিয়েছি",

        // connectors / common function words
        "এবং", "কিন্তু", "তবে", "তাই", "যদি", "যদিও", "কারণ", "অথবা", "বা", "না",
        "খুব", "অনেক", "একটু", "একদম", "সত্যিই", "হয়তো", "শুধু", "সব", "সবাই",

        // everyday nouns / phrases
        "ভালো", "ভালোবাসি", "ভালোবাসা", "খারাপ", "সুন্দর",
        "আজ", "আজকে", "কাল", "আগামীকাল", "এখন", "তখন", "পরে", "আগে",
        "বাসা", "বাড়ি", "স্কুল", "কলেজ", "অফিস", "কাজ", "সমস্যা",
        "ফোন", "ফেসবুক", "মেসেজ", "গান", "ছবি", "টাকা",
        "ধন্যবাদ", "দুঃখিত", "প্লিজ", "অবশ্যই", "স্বাগতম",
        "নাম", "ঠিকানা", "সময়", "দিন", "রাত", "সকাল", "বিকাল",
        "কথা", "প্রশ্ন", "উত্তর", "কারণ", "মানে"
    ).distinct()
}
