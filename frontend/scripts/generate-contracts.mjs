/**
 * 契约类型生成脚本。
 *
 * 输入：contracts/openapi.yaml、contracts/ws-events.schema.json
 * 输出：frontend/src/generated/*.ts（禁止手工修改）
 *
 * 运行：npm run generate:contracts
 */
import { execFileSync } from 'node:child_process'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { compileFromFile } from 'json-schema-to-typescript'

const here = dirname(fileURLToPath(import.meta.url))
const frontendRoot = resolve(here, '..')
const repoRoot = resolve(frontendRoot, '..')
const contractsDir = resolve(repoRoot, 'contracts')
const outDir = resolve(frontendRoot, 'src/generated')

const banner = [
  '/* eslint-disable */',
  '/**',
  ' * 本文件由 scripts/generate-contracts.mjs 自动生成，请勿手工修改。',
  ' * 契约来源：contracts/openapi.yaml、contracts/ws-events.schema.json',
  ' */',
  '',
].join('\n')

mkdirSync(outDir, { recursive: true })

const openapiPath = resolve(contractsDir, 'openapi.yaml')
const openapiOut = resolve(outDir, 'openapi.ts')
execFileSync(
  process.execPath,
  [resolve(frontendRoot, 'node_modules/openapi-typescript/bin/cli.js'), openapiPath, '-o', openapiOut],
  { stdio: 'inherit', cwd: frontendRoot },
)
writeFileSync(openapiOut, banner + readFileSync(openapiOut, 'utf8'), 'utf8')

const wsSchemaPath = resolve(contractsDir, 'ws-events.schema.json')
const wsTypes = await compileFromFile(wsSchemaPath, {
  cwd: contractsDir,
  bannerComment: '',
  additionalProperties: false,
  style: { semi: false, singleQuote: true },
})
writeFileSync(resolve(outDir, 'ws-events.ts'), banner + wsTypes, 'utf8')

console.log('契约类型已生成到 src/generated/')