package com.hemel.lenspilot.vision

/**
 * Label tables for the two bundled TFLite models. Index position in each
 * array = the class index the model outputs — order must match training
 * exactly (verified against the source dataset/data.yaml, not guessed).
 */
object IconLabels {

    /** detector_best_fp16.tflite — 21 Android UI element classes.
     * Output shape [1, 25, 8400] = 4 bbox coords + these 21 classes. */
    val DETECTOR_CLASSES = arrayOf(
        "BackgroundImage", "Bottom_Navigation", "Card", "CheckBox", "Checkbox",
        "CheckedTextView", "Drawer", "EditText", "Icon", "Image", "Map", "Modal",
        "Multi_Tab", "PageIndicator", "Remember", "Spinner", "Switch", "Text",
        "TextButton", "Toolbar", "UpperTaskBar"
    )

    /** Index of the "Icon" class — the only detector class we ever try to
     * classify further. Everything else (Text, TextButton, EditText...)
     * already carries its own readable label from OCR/accessibility. */
    const val DETECTOR_ICON_CLASS_INDEX = 8

    /** classifier_best_fp16.tflite — 168 specific icon types.
     * Output shape [1, 168]. Order = alphabetical (Ultralytics
     * classification training reads class folders via an ImageFolder-style
     * loader, which sorts folder names alphabetically). */
    val CLASSIFIER_CLASSES = arrayOf(
        "add_contact", "add_plus", "ai_sparkle", "app_logo", "app_logo_applemusic",
        "app_logo_instagram", "app_logo_m", "app_logo_messenger", "app_logo_musicnote",
        "app_logo_tiktok", "app_logo_vk", "apps_grid", "archive_box", "arrow_diagonal",
        "arrow_left", "arrow_right", "arrow_turn_right", "arrow_up", "attachment_paperclip",
        "back_arrow", "backspace_delete", "badge_star", "bookmark", "calendar_date",
        "camera", "cart_add", "cart_shopping", "cast_device", "chart_bar", "chart_trend",
        "chat_comment", "chat_messenger", "checkbox_checked", "checkmark_confirm",
        "chevron_double_down", "chevron_down", "chevron_right", "chevron_up",
        "clean_broom", "clipboard_list", "clock_time", "close", "cloud_download",
        "cloud_offline", "color_palette", "copy_duplicate", "crop_frame", "device_sync",
        "document_file", "document_scan", "document_text", "double_check",
        "download_arrow", "edit_compose", "edit_note", "edit_pencil", "emoji_smile",
        "emoji_wink", "expand_fullscreen", "eye_off", "eye_view", "filter_lines",
        "flag_report", "flame_streak", "flash_boost", "flash_off", "flashlight",
        "folder", "font_size", "globe_language", "google_login", "gps_target",
        "group_contacts", "hashtag", "heart_broken", "help_question", "history_recent",
        "home", "idea_lightbulb", "image_gallery", "inbox_tray", "info_circle",
        "keyboard", "layers_stack", "like_heart", "link_attachment", "list_view",
        "loading_spinner", "location_pin", "lock_security", "login", "logout",
        "mail_envelope", "megaphone_announcement", "mention_at", "menu", "mic_mute",
        "mic_voice", "minus_remove", "mobile_device", "more_options", "music_note",
        "music_record", "navigation_direction", "notification_badge", "notification_bell",
        "notification_dot", "notification_mute", "open_external", "pause_button",
        "phone_call", "placeholder_avatar", "placeholder_image", "play_button",
        "power_plug", "print", "privacy_icon", "profile_user", "profile_verified",
        "qr_code_scan", "refresh_sync", "repeat_loop", "reply_arrow", "resize_expand",
        "robot_ai", "screen_rotate", "search", "send_message", "settings_gear",
        "settings_sliders", "share_forward", "share_network", "shield_security",
        "shop_store", "shuffle", "skip_next", "skip_previous", "sort_updown",
        "speed_gauge", "split_view", "star_favorite", "sticker_emoji", "swap_arrows",
        "tag_price", "terminal_code", "thumbs_down", "thumbs_up", "timer_badge",
        "timer_clock", "timer_preset", "toggle_switch", "train_transit", "translate",
        "trash_delete", "tv_display", "undo", "unlock_security", "upload_arrow",
        "verified_badge", "video_call", "video_call_off", "video_reel", "voice_message",
        "volume_mute", "volume_speaker", "warning_alert", "wifi_off", "wifi_signal"
    )
}
