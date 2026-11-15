"""Group guard: the bot does nothing for any group or supergroup message.

install(client) wraps every handler registered on the client so it returns
immediately when the event comes from a group or supergroup. Private chats and
broadcast channels (the owner's channel import) are not affected. Wrapping the
callback, not the event filter, because catch-all NewMessage() handlers skip
their filter.
"""
import functools

from telethon import types


async def _in_group(event):
    try:
        peer = getattr(getattr(event, "message", None), "peer_id", None) or getattr(event, "_chat_peer", None)
        if isinstance(peer, types.PeerUser):
            return False
        if isinstance(peer, (types.PeerChat, getattr(types, "PeerChatForbidden", types.PeerChat))):
            return True
        if isinstance(peer, types.PeerChannel):
            chat = await event.get_chat()
            if getattr(chat, "megagroup", False) or getattr(chat, "gigagroup", False):
                return True
            return not bool(getattr(chat, "broadcast", False))   # unknown kind: fail closed
        return bool(event.is_group)
    except Exception:
        return True


def install(client):
    orig = client.add_event_handler

    def guarded(callback, event=None):
        @functools.wraps(callback)
        async def wrapper(e):
            if await _in_group(e):
                return None
            return await callback(e)
        return orig(wrapper, event)

    client.add_event_handler = guarded
    return client
