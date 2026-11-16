package com.imgmine.agentic;

/** Built-in operating guides for daily-use apps. Injected when that app is in the foreground. */
public final class AppKnowledge {

    /** Compact cheatsheet for the foreground package, or null. Token-aware: short lines. */
    public static String guideFor(String pkg) {
        if (pkg == null) return null;
        String p = pkg.toLowerCase();
        if (p.contains("whatsapp")) return "WHATSAPP: Chats list = main screen. Search icon (top) to find a contact. Open chat -> bottom input to type, arrow to send. Attach = + or pin icon. New chat = floating button bottom-right. Long-press a chat/message for options. Tabs: Chats/Updates/Calls.";
        if (p.contains("telegram")) return "TELEGRAM: Chat list = main. Search = magnifier top. Open chat -> type at bottom, arrow sends. Attach = pin icon. New message = pencil button. Long-press for options. Saved Messages = own cloud storage.";
        if (p.contains("gallery3d") || p.contains("photos") || p.contains("gallery")) return "GALLERY/PHOTOS: Grid of photos/videos, newest first (or Albums tab). Tap thumbnail to open. In photo view: share icon to share, edit to edit, trash to delete. Search icon in Google Photos finds people/things. Swipe to browse.";
        if (p.contains("android.vending") || p.contains("playstore")) return "PLAY STORE: Search bar on top. App page: Install/Open button, reviews below. My apps: profile icon top-right -> Manage apps. Categories at bottom.";
        if (p.contains("calculator")) return "CALCULATOR: Tap digits and operators directly. C clears. Scientific: rotate or expand arrow. Result shows on top.";
        if (p.contains("settings")) return "SETTINGS: Search bar on top finds any setting fastest. Common: Wi-Fi, Bluetooth, Display, Apps, Battery. Samsung: Connections/Battery sections. Tap item to open, toggles switch on/off.";
        if (p.contains("discord")) return "DISCORD: Servers on left rail. Channel list after opening server. # = text channels. Bottom input to type. Search = top-right icon. DMs = top-left icon. Long-press message for react/reply.";
        if (p.contains("youtube")) return "YOUTUBE: Home feed. Search = magnifier top-right. Tap video to play. Player: fullscreen icon bottom-right of video, like/subscribe below. Shorts/Subscriptions tabs at bottom. Your videos: profile icon -> Your channel.";
        if (p.contains("chrome")) return "CHROME: Address/search bar on top (tap to type URL or search). Tabs = square icon with number. 3-dot menu: bookmarks, history, downloads, share, 'Add to Home screen'. Long-press link for options.";
        if (p.contains("instagram")) return "INSTAGRAM: Home feed. Search = magnifier tab. DMs = arrow/messenger icon top-right. Camera/story = top-left or +. Post = + bottom. Long-press story for options.";
        if (p.contains("camera")) return "CAMERA: Big shutter button center-bottom. Swipe or mode names to switch Photo/Video/Portrait. Top bar: flash/timer/settings. Gallery preview = thumbnail bottom corner.";
        if (p.contains("dialer") || p.contains(".contacts") || p.contains("incallui")) return "PHONE/CONTACTS: Keypad tab to dial. Contacts tab + search. Recents = call log. Tap contact -> call/message icons.";
        if (p.contains("messaging") || p.contains("mms")) return "MESSAGES (SMS): Conversation list. Search top. Open chat -> type at bottom. New message = floating + button.";
        if (p.contains("gmail")) return "GMAIL: Inbox list. Search bar top ('Search in mail'). Compose = floating + / pencil. Open mail -> reply arrow at bottom. Swipe message to archive.";
        if (p.contains("maps")) return "GOOGLE MAPS: Search bar top. Tap result -> Directions button. Start = navigation. Layers icon for satellite/traffic.";
        if (p.contains("spotify")) return "SPOTIFY: Home/Search/Library tabs bottom. Search tab -> type song. Tap song to play. Player: bottom bar expands; heart = like.";
        if (p.contains("twitter") || p.contains(".x.") || p.equals("com.x")) return "X (TWITTER): Home feed. Search = magnifier tab. New post = + button. DMs = envelope tab. Tap post to open replies.";
        if (p.contains("facebook")) return "FACEBOOK: Feed = home. Search = magnifier top. Menu = 3 lines right. Messenger separate app. Post box on top of feed.";
        return null;
    }
}
