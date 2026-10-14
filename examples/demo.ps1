$ErrorActionPreference = 'Stop'
$body = '{"productId":1,"quantity":2}'
$headers = @{ 'Idempotency-Key' = 'demo-order-001' }
$result = Invoke-RestMethod -Method Post -Uri 'http://localhost:8082/api/orders' -ContentType 'application/json' -Body $body -Headers $headers
$result | ConvertTo-Json -Depth 5
# 同じリクエストを再送するとreplayed=trueとなり、在庫は追加で減算されません。
Invoke-RestMethod -Method Post -Uri 'http://localhost:8082/api/orders' -ContentType 'application/json' -Body $body -Headers $headers | ConvertTo-Json -Depth 5
Invoke-RestMethod -Uri 'http://localhost:8082/api/orders' | ConvertTo-Json -Depth 5
