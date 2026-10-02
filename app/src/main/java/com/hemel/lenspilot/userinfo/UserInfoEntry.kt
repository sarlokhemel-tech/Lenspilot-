package com.hemel.lenspilot.userinfo

/**
 * One row of the "আমার তথ্য" table — [label] is the narrow left column
 * (কিসের তথ্য, e.g. "নাম"), [value] is the wider right column (তার
 * তথ্য, e.g. "হিমেল"). Both columns grow with content — this class just
 * carries the two strings; [dialog_user_info.xml]'s row layout is what
 * actually lets them wrap to any height.
 *
 * Persisted via Prefs.userInfoEntries/setUserInfoEntries as a plain JSON
 * array, and sent to the backend unchanged as the "user_info" field on
 * every /api/chat, /api/workflow/plan, /api/analyze-screen and
 * /api/browser-action request — see app.py's build_user_info_block(),
 * which only turns non-blank rows into extra system-prompt text.
 */
data class UserInfoEntry(
    var label: String = "",
    var value: String = ""
)
