package com.imgmine.agentic;

import java.util.HashMap;
import java.util.Map;

/**
 * Built-in operating playbooks for daily-use apps. Injected once when that app enters the
 * foreground (token-aware: only the matching playbook is sent, never the whole library).
 * Style: concrete step patterns a connected AI can follow immediately.
 */
public final class AppKnowledge {
    private static final Map<String, String> G = new HashMap<>();
    static {
        G.put("whatsapp", "WHATSAPP playbook:\n" +
            "- Send message: Chats tab -> search icon (top) -> type name -> tap contact -> type in bottom INPUT -> send arrow (appears right of input after typing).\n" +
            "- New chat: floating chat button bottom-right -> find contact.\n" +
            "- Read older messages: open chat, swipe up (content scrolls down).\n" +
            "- Attach media/doc: + or pin icon inside chat. Voice note: mic icon (hold).\n" +
            "- Tabs at top/bottom: Chats, Updates (status), Communities, Calls.\n" +
            "- Long-press a chat or message: delete, forward, archive, mute, pin.\n" +
            "- If send arrow missing: the INPUT is empty - type first.");
        G.put("telegram", "TELEGRAM playbook:\n" +
            "- Send message: chat list -> magnifier (top) -> name -> open -> type bottom -> arrow sends.\n" +
            "- New message: pencil button. Attach: pin icon. Saved Messages = own cloud.\n" +
            "- Long-press message: forward/delete/react. Search inside chat: open chat -> 3-dot -> Search.");
        G.put("youtube", "YOUTUBE playbook:\n" +
            "- Search: magnifier top-right -> type query -> tap result. Filter: 3-dot/filter chip.\n" +
            "- Play: tap thumbnail. Player controls: tap video to show, fullscreen icon bottom-right.\n" +
            "- Like/subscribe/comment: below the video. Comments need scroll down.\n" +
            "- Shorts tab = vertical feed (swipe up for next). Subscriptions/Library tabs at bottom.\n" +
            "- Your uploads: profile icon (top-right) -> Your channel.");
        G.put("instagram", "INSTAGRAM playbook:\n" +
            "- Scroll feed: Home tab, swipe up. Stories: circles on top, tap to view, tap right side = next.\n" +
            "- Search: magnifier tab -> type. DMs: messenger icon top-right -> same as chat apps.\n" +
            "- Like: double_tap the post photo or heart icon. Comment: speech icon.\n" +
            "- Post: + button (bottom or top) -> pick photo -> Next -> Share. Reels: video tab.\n" +
            "- Profile: bottom-right tab -> Edit profile / settings via 3-lines top-right.");
        G.put("chrome", "CHROME playbook:\n" +
            "- Search/visit: tap top address bar -> type URL or query -> tap suggestion/Go.\n" +
            "- Tabs: square-with-number icon (top) -> switch or + for new. Close tab: X on tab card.\n" +
            "- 3-dot menu: Bookmarks, History, Downloads, Share, Find in page, Desktop site.\n" +
            "- WebView pages may expose few ids - use look + coordinates if dump is thin.");
        G.put("settings", "SETTINGS playbook:\n" +
            "- FASTEST: Search bar on top -> type the setting name -> tap the result.\n" +
            "- Common paths: Wi-Fi/Bluetooth under Network/Connections; Display; Apps (per-app permissions, storage, uninstall); Battery; Sound; Security/Screen lock.\n" +
            "- Toggles: tap the switch. Sub-screens: back returns to the list.");
        G.put("gmail", "GMAIL playbook:\n" +
            "- Send mail: floating Compose (+) -> To/Subject/body fields -> arrow/plane icon sends.\n" +
            "- Search: 'Search in mail' bar on top. Open mail -> Reply arrow at bottom.\n" +
            "- Swipe a mail in list = archive. Labels/folders: hamburger menu top-left.");
        G.put("maps", "GOOGLE MAPS playbook:\n" +
            "- Find place: search bar top -> type -> tap result card.\n" +
            "- Navigate: Directions button -> choose mode (car/walk/bike) -> Start.\n" +
            "- Nearby: category chips under search (food, petrol, ATMs). Layers icon: satellite/traffic.");
        G.put("spotify", "SPOTIFY playbook:\n" +
            "- Play song: Search tab -> type name -> tap song. Player bar at bottom expands on tap.\n" +
            "- Controls: play/pause center, skip arrows, heart = like, shuffle/repeat icons.\n" +
            "- Library tab = playlists/liked. Create playlist: Library -> +.");
        G.put("twitter", "X (TWITTER) playbook:\n" +
            "- Scroll home feed. Search = magnifier tab. Post: + / feather button -> type -> Post.\n" +
            "- Reply/like/retweet icons under each post. DMs: envelope tab. Profile: top-left avatar.");
        G.put("facebook", "FACEBOOK playbook:\n" +
            "- Feed scrolls vertically. Search: magnifier top. Post box at top of feed.\n" +
            "- Menu: 3-lines (right). Reels/Marketplace/Notifications in the nav bar.\n" +
            "- Messenger is a separate app for chats.");
        G.put("photos", "PHOTOS/GALLERY playbook:\n" +
            "- Grid newest-first; Albums tab for folders. Tap thumbnail to open.\n" +
            "- In photo view: share icon (share to apps), pencil (edit), trash (delete).\n" +
            "- Google Photos search finds people/places/things. Swipe left/right to browse.");
        G.put("vending", "PLAY STORE playbook:\n" +
            "- Install app: search bar top -> type name -> tap result -> Install. Open after install.\n" +
            "- Updates: profile icon top-right -> Manage apps & device.\n" +
            "- NEVER buy paid apps without user confirmation. Reviews below app info.");
        G.put("dialer", "PHONE/CONTACTS playbook:\n" +
            "- Call a contact: Contacts tab -> search -> tap contact -> call icon. Keypad tab = dial number.\n" +
            "- Recents tab = call log; tap entry = call back. Favorites = starred.\n" +
            "- CALLING IS HIGH RISK: confirm the number/contact with the user before tapping call.");
        G.put("messaging", "MESSAGES (SMS) playbook:\n" +
            "- Send SMS: conversation list -> open chat OR floating + for new -> type bottom -> arrow.\n" +
            "- Search bar on top. Long-press conversation = delete/archive.");
        G.put("camera", "CAMERA playbook:\n" +
            "- Shutter = big center-bottom button. Mode names/swipe = Photo/Video/Portrait.\n" +
            "- Top bar: flash, timer, settings gear. Preview thumbnail (corner) = open gallery.\n" +
            "- pinch to zoom on the viewfinder.");
        G.put("discord", "DISCORD playbook:\n" +
            "- Servers: left rail icons. Channel list opens with the server; # = text channel.\n" +
            "- Type at bottom input -> send arrow. Long-press message = react/reply.\n" +
            "- DMs: top-left/Chat icon. Search: top-right magnifier.");
        G.put("calculator", "CALCULATOR: tap digits/operators directly. C or AC clears. Expand arrow/rotate = scientific. Result on top.");
        G.put("files", "FILES MANAGER: Browse/Categories views. Search = magnifier. Long-press file = select -> share/delete/move icons on top. Storage locations in side menu.");
        G.put("clock", "CLOCK: Tabs: Alarm/Clock/Timer/Stopwatch. + adds alarm -> set time -> OK. Tap alarm to edit, switch toggles it.");
        G.put("snapchat", "SNAPCHAT: Camera opens first (shutter = snap, hold = video). Chat: swipe right or chat tab. Stories/Spotlight: swipe left. Search bar top.");
        G.put("paytm", "PAYMENT APP (Paytm/GPay/PhonePe style): HIGH RISK - NEVER send money or complete a payment without explicit step-by-step user confirmation. Scan & Pay, UPI id entry, and PIN screens must all be confirmed via ask.");
    }

    /** Compact playbook for the foreground package, or null. */
    public static String guideFor(String pkg) {
        if (pkg == null) return null;
        String p = pkg.toLowerCase();
        if (p.contains("paytm") || p.contains("phonepe") || p.contains("google.android.apps.nbu.paisa") || p.contains("bhim"))
            return G.get("paytm");
        if (p.contains("whatsapp")) return G.get("whatsapp");
        if (p.contains("telegram")) return G.get("telegram");
        if (p.contains("youtube")) return G.get("youtube");
        if (p.contains("instagram")) return G.get("instagram");
        if (p.contains("chrome") || p.contains("browser")) return G.get("chrome");
        if (p.contains("settings")) return G.get("settings");
        if (p.contains("gmail")) return G.get("gmail");
        if (p.contains("maps")) return G.get("maps");
        if (p.contains("spotify")) return G.get("spotify");
        if (p.contains("twitter") || p.contains(".x.") || p.equals("com.x")) return G.get("twitter");
        if (p.contains("facebook")) return G.get("facebook");
        if (p.contains("photos") || p.contains("gallery3d") || p.contains("gallery")) return G.get("photos");
        if (p.contains("vending") || p.contains("playstore")) return G.get("vending");
        if (p.contains("dialer") || p.contains(".contacts") || p.contains("incallui")) return G.get("dialer");
        if (p.contains("messaging") || p.contains("mms")) return G.get("messaging");
        if (p.contains("camera")) return G.get("camera");
        if (p.contains("discord")) return G.get("discord");
        if (p.contains("calculator")) return G.get("calculator");
        if (p.contains("files") || p.contains("documentsui") || p.contains("filemanager")) return G.get("files");
        if (p.contains("clock") || p.contains("alarm")) return G.get("clock");
        if (p.contains("snapchat")) return G.get("snapchat");
        return null;
    }
}
