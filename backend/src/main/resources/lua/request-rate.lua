local user = tonumber(redis.call('GET', KEYS[1]) or '0')
local department = tonumber(redis.call('GET', KEYS[2]) or '0')
if user >= tonumber(ARGV[1]) or department >= tonumber(ARGV[2]) then return 0 end
redis.call('INCR', KEYS[1])
redis.call('INCR', KEYS[2])
if user == 0 then redis.call('PEXPIRE', KEYS[1], ARGV[3]) end
if department == 0 then redis.call('PEXPIRE', KEYS[2], ARGV[3]) end
return 1
