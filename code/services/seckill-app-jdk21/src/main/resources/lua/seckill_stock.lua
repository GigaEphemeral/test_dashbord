-- Atomic seckill stock reservation.
-- KEYS[1] = stock key, ARGV[1] = requested quantity.
-- Returns the remaining stock on success, -1 when not enough stock, -2 when
-- the stock key does not exist (not seeded / expired).
local stock = redis.call('GET', KEYS[1])
if not stock then
  return -2
end
local available = tonumber(stock)
local quantity = tonumber(ARGV[1])
if available < quantity then
  return -1
end
return redis.call('DECRBY', KEYS[1], quantity)
