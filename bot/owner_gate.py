"""Owner gate: in private chats the bot answers only the owner.

install(client, owner_id) wraps every handler registered on the client so it
returns immediately (silently) when the event is a private message from a
non-owner. Broadcast channels (the owner's channel import in db_mode) and
group chats (already handled by group_guard) are not affected, and db_mode's
own owner checks stay in force behind this gate. Same wrapping approach as
group_guard: wrap the callback, not the event filter, because catch-all
NewMessage() handlers skip their filter.
"""
import functools

from telethon import types


def _non_owner_private(event, owner_id):
    try:
        peer = getattr(getattr(event, "message", None), "peer_id", None) or getattr(event, "_chat_peer", None)
        if not isinstance(peer, types.PeerUser):
            return False   # channels and groups: not this gate's concern
        if owner_id is None:
            return True    # no owner provisioned: deny everyone (fail closed)
        return peer.user_id != owner_id
    except Exception:
        return False       # unknown shape: leave it to the handler's own checks


def install(client, owner_id):
    orig = client.add_event_handler

    def guarded(callback, event=None):
        @functools.wraps(callback)
        async def wrapper(e):
            if _non_owner_private(e, owner_id):
                return None
            return await callback(e)
        return orig(wrapper, event)

    client.add_event_handler = guarded
    return client
