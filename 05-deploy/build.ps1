# ============================================================================
# 12345 政务热线 — 05-deploy 一键打包脚本（在 Windows 开发机执行）
# 流程：01 npm build → 02 mvn package → 产物复制进 05-deploy → 03 AI源码同步 → 04 SQL同步
# 之后把 05-deploy 整个目录拷到虚拟机（任意 IP），执行 docker compose up -d --build
# 部署不依赖固定 IP：容器间用 compose 服务名、前端同源相对路径，IP 变化无需改任何配置
# ============================================================================
$ErrorActionPreference = "Stop"
$root = "D:\Days\Days-项目\12345政务热线"
$dep  = Join-Path $root "05-deploy"

# ---- 1. 前端（01-frontend）：npm run build → dist 复制到 05\frontend\dist ----
Write-Host "==> [1/4] 前端打包 (npm run build)..." -ForegroundColor Cyan
Push-Location (Join-Path $root "01-frontend")
npm run build
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "前端构建失败" }
Pop-Location
$distDst = Join-Path $dep "frontend\dist"
if (Test-Path $distDst) { Remove-Item $distDst -Recurse -Force }
Copy-Item (Join-Path $root "01-frontend\dist") $distDst -Recurse -Force
Write-Host "    dist 已复制到 05-deploy\frontend\dist" -ForegroundColor Green

# ---- 2. 后端（02-backend）：mvn package -DskipTests → jar 复制到 05\backend\app.jar ----
Write-Host "==> [2/4] 后端打包 (mvn package)..." -ForegroundColor Cyan
Push-Location (Join-Path $root "02-backend")
.\mvnw.cmd package -DskipTests
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "后端打包失败" }
Pop-Location
$jar = Get-ChildItem (Join-Path $root "02-backend\target") -Filter "02-backend-0.0.1-SNAPSHOT.jar" | Select-Object -First 1
Copy-Item $jar.FullName (Join-Path $dep "backend\app.jar") -Force
Write-Host "    app.jar 已复制到 05-deploy\backend" -ForegroundColor Green

# ---- 3. AI 服务（03-fastAPI）：app/ 包与 requirements.txt 同步到 05\ai ----
Write-Host "==> [3/4] AI 服务源码同步..." -ForegroundColor Cyan
$aiAppDst = Join-Path $dep "ai\app"
if (Test-Path $aiAppDst) { Remove-Item $aiAppDst -Recurse -Force }
Copy-Item (Join-Path $root "03-fastAPI\app") $aiAppDst -Recurse -Force
Get-ChildItem $aiAppDst -Recurse -Directory -Filter "__pycache__" | Remove-Item -Recurse -Force
Copy-Item (Join-Path $root "03-fastAPI\requirements.txt") (Join-Path $dep "ai\requirements.txt") -Force
Write-Host "    AI 源码已同步到 05-deploy\ai" -ForegroundColor Green

# ---- 4. 数据库脚本（04-document）：同步到 05\mysql\init（UTF-8 无 BOM，首行 SET NAMES utf8mb4） ----
# 说明：除 30-deploy-all-in-one.sql（合并版，不用于 init 自动执行）外全部同步；
#       22 的预警测试规则会保留（28 只清 warning_record 不删规则）、24 全新建库时为空操作、29 铺布工单时间
Write-Host "==> [4/4] 数据库初始化脚本同步..." -ForegroundColor Cyan
$sqlList = @('00-init-all.sql','21-test-users.sql','22-test-warning.sql','23-test-appeals.sql',
             '24-clear-audit.sql','25-seed-classify.sql','26-seed-config.sql','27-seed-region.sql',
             '28-seed-orders.sql','29-time-adjust.sql')
$sqlDst = Join-Path $dep "mysql\init"
foreach ($n in $sqlList) {
    Copy-Item (Join-Path $root "04-document\$n") (Join-Path $sqlDst $n) -Force
    Write-Host "    $n 已同步" -ForegroundColor DarkGray
}
# 清理 05 中已废弃的旧脚本（不在同步清单内的 sql）
Get-ChildItem $sqlDst -Filter *.sql | Where-Object { $sqlList -notcontains $_.Name } | Remove-Item -Force
Write-Host "    SQL 已同步到 05-deploy\mysql\init" -ForegroundColor Green

Write-Host ""
Write-Host "全部打包完成。下一步：" -ForegroundColor Yellow
Write-Host "  1) 将 05-deploy 目录整体拷贝到虚拟机（任意 IP 均可，如 192.168.114.100）"
Write-Host "  2) 在虚拟机上执行：docker compose up -d --build"
Write-Host "  3) 浏览器访问 http://<虚拟机当前IP> （前端），API 走同源 /api/v1 反代"
