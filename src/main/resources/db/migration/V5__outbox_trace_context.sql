-- W3C traceparent of the request that produced the row. The publisher runs later on another thread, so the
-- trace context has to travel with the row to parent the publish span on the originating request.
ALTER TABLE platform.outbox_events ADD COLUMN trace_parent varchar(55);
