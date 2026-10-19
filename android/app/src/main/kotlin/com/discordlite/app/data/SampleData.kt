package com.discordlite.app.data

import com.discordlite.designsystem.component.InvitePreview

/*
 * Placeholder data so the UI can be reviewed before the backend exists (Phase 1+ replaces this
 * with real repositories). Nothing here is persisted.
 */

const val ME = "me"

data class User(val id: String, val name: String, val username: String, val online: Boolean = false)

data class Message(
    val id: Long,
    val authorId: String,
    val text: String,
    val time: String,
    val invite: InvitePreview? = null,
)

data class Chat(
    val id: String,
    val title: String,
    val members: List<User>,
    val isGroup: Boolean,
    val time: String,
    val unread: Int,
    val messages: List<Message>,
) {
    val lastMessage: String get() = messages.lastOrNull()?.let { if (it.invite != null && it.text.isBlank()) "Sent an invite" else it.text }.orEmpty()
    val online: Boolean get() = !isGroup && members.firstOrNull()?.online == true
}

data class Channel(val id: String, val name: String, val unread: Boolean = false, val voice: Boolean = false)

data class Category(val name: String, val channels: List<Channel>)

data class Server(
    val id: String,
    val name: String,
    val online: Int,
    val members: Int,
    val unread: Boolean,
    val mentions: Int,
    val inviteCode: String,
    val categories: List<Category>,
) {
    val invite get() = InvitePreview(inviteCode, name, online, members)
}

data class Post(
    val id: String,
    val author: User,
    val game: String,
    val caption: String,
    val likes: Int,
    val comments: Int,
    val time: String,
    val slides: Int,
)

data class Story(val user: User, val seen: Boolean)

object SampleData {
    val me = User(ME, "Ripun", "ripun", online = true)

    val maya = User("maya", "Maya Chen", "mayaplays", online = true)
    val aarav = User("aarav", "Aarav Singh", "aarav")
    val rohan = User("rohan", "Rohan Das", "rohan.gg", online = true)
    val zoe = User("zoe", "Zoe Park", "zoe")
    val kenji = User("kenji", "Kenji Ito", "kenji", online = true)
    val lina = User("lina", "Lina Bose", "linab")
    val sam = User("sam", "Sam Rivera", "samr")

    val friends = listOf(maya, rohan, kenji, aarav, zoe, lina, sam)
    fun user(id: String): User = if (id == ME) me else friends.first { it.id == id }

    val servers = listOf(
        Server(
            id = "nightowls", name = "Night Owls Gaming", online = 1204, members = 8930, unread = true, mentions = 2,
            inviteCode = "nightowls",
            categories = listOf(
                Category("Information", listOf(Channel("welcome", "welcome"), Channel("announcements", "announcements", unread = true))),
                Category("Text channels", listOf(Channel("general", "general", unread = true), Channel("clips", "clips"), Channel("lfg", "looking-for-group", unread = true), Channel("memes", "memes"))),
                Category("Voice channels", listOf(Channel("lobby", "Lobby", voice = true), Channel("ranked", "Ranked grind", voice = true))),
            ),
        ),
        Server(
            "valorant", "Valorant India", 3412, 22018, unread = true, mentions = 0, inviteCode = "valorantin",
            categories = listOf(
                Category("Text channels", listOf(Channel("general", "general", unread = true), Channel("agents", "agent-talk"), Channel("lfg", "lfg-ranked"))),
                Category("Voice channels", listOf(Channel("v1", "Squad 1", voice = true), Channel("v2", "Squad 2", voice = true))),
            ),
        ),
        Server(
            "mc", "Minecraft Builders", 210, 1588, unread = false, mentions = 0, inviteCode = "mcbuild",
            categories = listOf(Category("Text channels", listOf(Channel("general", "general"), Channel("builds", "showcase"), Channel("redstone", "redstone")))),
        ),
        Server(
            "indie", "Indie Devs", 96, 740, unread = false, mentions = 1, inviteCode = "indiedevs",
            categories = listOf(Category("Text channels", listOf(Channel("general", "general"), Channel("devlog", "devlogs"), Channel("feedback", "feedback")))),
        ),
        Server(
            "apex", "Apex Squad", 58, 312, unread = true, mentions = 0, inviteCode = "apexsquad",
            categories = listOf(Category("Text channels", listOf(Channel("general", "general", unread = true), Channel("clips", "clips")))),
        ),
    )

    fun server(id: String) = servers.first { it.id == id }

    private var nextId = 1L
    private fun m(author: User, text: String, time: String, invite: InvitePreview? = null) = Message(nextId++, author.id, text, time, invite)

    val chats = listOf(
        Chat(
            "c-maya", maya.name, listOf(maya), isGroup = false, time = "9:41", unread = 2,
            messages = listOf(
                m(maya, "yo did you see the new agent trailer??", "9:30"),
                m(me, "YES. the wall ability looks broken 😭", "9:31"),
                m(me, "we need to try it tonight", "9:31"),
                m(maya, "ok so I made a server for our squad", "9:40"),
                m(maya, "", "9:41", invite = servers[0].invite),
            ),
        ),
        Chat(
            "c-squad", "Ranked Squad 🏆", listOf(rohan, kenji, aarav), isGroup = true, time = "9:12", unread = 5,
            messages = listOf(
                m(rohan, "who's online tonight", "8:58"),
                m(kenji, "me after 10", "9:02"),
                m(aarav, "I'm in, need 2 more wins for plat", "9:05"),
                m(me, "same lol, let's go", "9:10"),
                m(rohan, "clip from last night is up on my profile 🔥", "9:12"),
            ),
        ),
        Chat(
            "c-rohan", rohan.name, listOf(rohan), isGroup = false, time = "Yesterday", unread = 0,
            messages = listOf(
                m(rohan, "gg that last round was insane", "22:14"),
                m(me, "that 1v3 clutch tho 👀", "22:15"),
            ),
        ),
        Chat(
            "c-zoe", zoe.name, listOf(zoe), isGroup = false, time = "Yesterday", unread = 0,
            messages = listOf(m(me, "sending you the build seed", "18:02"), m(zoe, "thank youuu 💚", "18:04")),
        ),
        Chat(
            "c-kenji", kenji.name, listOf(kenji), isGroup = false, time = "Mon", unread = 0,
            messages = listOf(m(kenji, "elden ring dlc this weekend?", "20:30")),
        ),
        Chat(
            "c-lina", lina.name, listOf(lina), isGroup = false, time = "Sun", unread = 0,
            messages = listOf(m(lina, "loved your story haha", "11:00"), m(me, "😂 thanks", "11:20")),
        ),
    )

    fun chat(id: String) = chats.first { it.id == id }

    fun channelMessages(serverId: String, channelId: String): List<Message> = listOf(
        m(kenji, "welcome to #$channelId 👋", "8:01"),
        m(rohan, "anyone up for customs later?", "8:40"),
        m(rohan, "need 3 more", "8:40"),
        m(maya, "count me in", "8:52"),
        m(sam, "posted my setup in #clips", "9:05"),
        m(aarav, "that RGB is crazy", "9:07"),
    )

    val stories = listOf(
        Story(maya, seen = false),
        Story(rohan, seen = false),
        Story(kenji, seen = false),
        Story(zoe, seen = true),
        Story(aarav, seen = true),
        Story(lina, seen = true),
    )

    val posts = listOf(
        Post("p1", maya, "Valorant", "ace on haven with the new agent. I'm never recovering from this", 1284, 96, "2h", slides = 3),
        Post("p2", zoe, "Minecraft", "finished the cherry blossom village 🌸 took 3 weekends", 842, 41, "5h", slides = 2),
        Post("p3", kenji, "Elden Ring", "finally beat Malenia. 87 tries.", 2310, 203, "1d", slides = 1),
        Post("p4", rohan, "Apex Legends", "squad wipe in 12 seconds. wraith mains rise", 655, 27, "1d", slides = 1),
        Post("p5", lina, "Genshin Impact", "new team comp is so pretty", 433, 18, "2d", slides = 2),
    )

    val myPosts = listOf("Valorant", "Minecraft", "Apex Legends", "Elden Ring", "Valorant", "Fortnite", "Minecraft", "Genshin Impact", "Valorant")
}
