package com.hemel.lenspilot.vision

/**
 * Label table for ui_detector_w8a32.tflite — a single Ultralytics YOLOv8
 * detection model (task: "detect", quantize: "w8a32" — 8-bit weights,
 * float32 activations, so the Interpreter's input/output tensors are
 * still plain float32, no dequant math needed in Kotlin) that does BOTH
 * region detection AND icon classification in one pass, replacing the old
 * two-model detector_best_fp16.tflite + classifier_best_fp16.tflite pair.
 *
 * 188 classes total, in exact training order (verified against the
 * model's own embedded metadata.json "names" map — order here MUST match
 * that exactly, it's the class index the model outputs):
 *   - indices 0..19  = 20 structural/container classes (Card, Drawer,
 *     Toolbar, EditText, Switch, ...) — detected but NOT surfaced as
 *     elements (mirrors the old detector's "Icon" filter: everything that
 *     isn't a specific icon type gets ignored here too, since OCR/
 *     accessibility already cover text and the containers themselves
 *     aren't directly tappable targets worth highlighting).
 *   - indices 20..187 = 168 specific icon types (identical list to the old
 *     classifier's CLASSIFIER_CLASSES) — these ARE the labeled, clickable
 *     icon elements IconDetector.detect() returns as final results; no
 *     separate crop+classify step needed anymore, the detector already
 *     names them directly.
 */
object IconLabels {

    /** Output shape [1, 4 + 188, 8400] = 4 bbox coords + 188 class scores. */
    val DETECTOR_CLASSES = arrayOf(
        "BackgroundImage", "Bottom_Navigation", "Card", "CheckBox", "Checkbox",
        "CheckedTextView", "Drawer", "EditText", "Image", "Map", "Modal", "Multi_Tab",
        "PageIndicator", "Remember", "Spinner", "Switch", "Text", "TextButton",
        "Toolbar", "UpperTaskBar", "add_contact", "add_plus", "ai_sparkle", "app_logo",
        "app_logo_applemusic", "app_logo_instagram", "app_logo_m", "app_logo_messenger",
        "app_logo_musicnote", "app_logo_tiktok", "app_logo_vk", "apps_grid",
        "archive_box", "arrow_diagonal", "arrow_left", "arrow_right", "arrow_turn_right",
        "arrow_up", "attachment_paperclip", "back_arrow", "backspace_delete",
        "badge_star", "bookmark", "calendar_date", "camera", "cart_add", "cart_shopping",
        "cast_device", "chart_bar", "chart_trend", "chat_comment", "chat_messenger",
        "checkbox_checked", "checkmark_confirm", "chevron_double_down", "chevron_down",
        "chevron_right", "chevron_up", "clean_broom", "clipboard_list", "clock_time",
        "close", "cloud_download", "cloud_offline", "color_palette", "copy_duplicate",
        "crop_frame", "device_sync", "document_file", "document_scan", "document_text",
        "double_check", "download_arrow", "edit_compose", "edit_note", "edit_pencil",
        "emoji_smile", "emoji_wink", "expand_fullscreen", "eye_off", "eye_view",
        "filter_lines", "flag_report", "flame_streak", "flash_boost", "flash_off",
        "flashlight", "folder", "font_size", "globe_language", "google_login",
        "gps_target", "group_contacts", "hashtag", "heart_broken", "help_question",
        "history_recent", "home", "idea_lightbulb", "image_gallery", "inbox_tray",
        "info_circle", "keyboard", "layers_stack", "like_heart", "link_attachment",
        "list_view", "loading_spinner", "location_pin", "lock_security", "login",
        "logout", "mail_envelope", "megaphone_announcement", "mention_at", "menu",
        "mic_mute", "mic_voice", "minus_remove", "mobile_device", "more_options",
        "music_note", "music_record", "navigation_direction", "notification_badge",
        "notification_bell", "notification_dot", "notification_mute", "open_external",
        "pause_button", "phone_call", "placeholder_avatar", "placeholder_image",
        "play_button", "power_plug", "print", "privacy_icon", "profile_user",
        "profile_verified", "qr_code_scan", "refresh_sync", "repeat_loop", "reply_arrow",
        "resize_expand", "robot_ai", "screen_rotate", "search", "send_message",
        "settings_gear", "settings_sliders", "share_forward", "share_network",
        "shield_security", "shop_store", "shuffle", "skip_next", "skip_previous",
        "sort_updown", "speed_gauge", "split_view", "star_favorite", "sticker_emoji",
        "swap_arrows", "tag_price", "terminal_code", "thumbs_down", "thumbs_up",
        "timer_badge", "timer_clock", "timer_preset", "toggle_switch", "train_transit",
        "translate", "trash_delete", "tv_display", "undo", "unlock_security",
        "upload_arrow", "verified_badge", "video_call", "video_call_off", "video_reel",
        "voice_message", "volume_mute", "volume_speaker", "warning_alert", "wifi_off",
        "wifi_signal"
    )

    /** First index that's a specific, nameable icon type rather than a
     * generic structural/container class — see class doc above. Detections
     * with classIndex < this are discarded by IconDetector; only
     * classIndex >= this are returned as labeled icon elements. */
    const val DETECTOR_ICON_START_INDEX = 20
}
