import fs from 'node:fs';
import {sanitizedCredentials} from './portable-core.mjs';
try { process.stdout.write(sanitizedCredentials(fs.readFileSync(0,'utf8'))); }
catch { process.stderr.write('CREDENTIAL_TRIM_FAILED\n');process.exitCode=65; }
