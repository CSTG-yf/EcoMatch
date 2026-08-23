-- Backfill only legacy chats whose historical queries all belong to one agent.
-- Empty and mixed-agent chats intentionally remain unbound for manual review.
UPDATE s2_chat
SET agent_id = (
    SELECT MIN(q.agent_id)
    FROM s2_chat_query q
    WHERE q.chat_id = s2_chat.chat_id
      AND q.agent_id IS NOT NULL
)
WHERE s2_chat.agent_id IS NULL
  AND 1 = (
      SELECT COUNT(DISTINCT q.agent_id)
      FROM s2_chat_query q
      WHERE q.chat_id = s2_chat.chat_id
        AND q.agent_id IS NOT NULL
  );
