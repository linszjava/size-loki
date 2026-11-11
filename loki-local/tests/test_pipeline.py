#!/usr/bin/env python3
"""Exercise the actual Alloy pipeline in an isolated container; never write to Loki."""
import json
import re
import subprocess
import tempfile
import time
import uuid
from pathlib import Path


def docker(*args):
    return subprocess.check_output(['docker', *args], text=True, stderr=subprocess.STDOUT)


def field(line, name):
    match = re.search(r'\b' + name + r'=("(?:\\.|[^"\\])*"|\S+)', line)
    value = match.group(1)
    return json.loads(value) if value.startswith('"') else value


def main():
    config = Path(__file__).resolve().parents[1] / 'config.alloy'
    rows = []
    for status in [200, 302, 399, 400, 404, 499, 500, 503, 599, 600, 0, '404', 'oops', None]:
        rows.append({'service': {'name': 'gateway-service'}, 'log': {'level': 'INFO'},
                     'event_action': 'gateway_request_completed', 'status': status,
                     'requestId': str(status), 'message': 'quote " newline\n 中文',
                     'extra': {'keep': True}})
    rows += [
        {'service': {'name': 'order-service'}, 'log': {'level': 'ERROR'}, 'status': 404},
        {'service': {'name': 'gateway-service'}, 'log': {'level': 'INFO'},
         'event_action': 'gateway_request_started'},
        {'service.name': 'gateway-service', 'log.level': 'INFO',
         'event_action': 'gateway_request_completed', 'status': 404},
    ]
    inputs = [json.dumps(row, ensure_ascii=False) for row in rows] + ['not-json']
    name = 'size-loki-pipeline-test-' + uuid.uuid4().hex[:10]
    with tempfile.TemporaryDirectory(prefix='size-loki-test-', dir='/tmp') as tmp:
        directory = Path(tmp).resolve()
        source = config.read_text().replace('/var/log/size-loki/*-service.json', '/fixtures/input.json')
        source = source.replace('loki.write.local.receiver', 'loki.echo.test.receiver')
        source = source[:source.index('loki.write "local"')] + 'loki.echo "test" {}\n'
        (directory / 'config.alloy').write_text(source)
        (directory / 'input.json').write_text('\n'.join(inputs) + '\n')
        try:
            docker('run', '-d', '--name', name, '--mount',
                   f'type=bind,src={directory},dst=/fixtures,readonly',
                   'grafana/alloy:v1.19.2', 'run', '--storage.path=/tmp/alloy-data', '/fixtures/config.alloy')
            deadline = time.monotonic() + 20
            while True:
                output = docker('logs', name)
                entries = [line for line in output.splitlines() if 'msg="received log entry"' in line]
                if len(entries) >= len(inputs):
                    break
                if time.monotonic() >= deadline:
                    raise AssertionError('Alloy did not emit all samples:\n' + output)
                time.sleep(0.25)
            assert len(entries) == len(inputs)
            for original, line in zip(inputs, entries):
                cleaned = field(line, 'entry')
                labels = field(line, 'labels')
                if original == 'not-json':
                    assert cleaned == original
                    continue
                before, after = json.loads(original), json.loads(cleaned)
                status = str(before.get('status'))
                service = before.get('service', {}).get('name', before.get('service.name'))
                applies = (service == 'gateway-service'
                           and before.get('event_action') == 'gateway_request_completed'
                           and re.fullmatch('[1-5][0-9]{2}', status))
                if not applies:
                    assert cleaned == original, (before, after)
                    continue
                code = int(status)
                level = 'ERROR' if code >= 400 else 'INFO'
                assert after.get('log', {}).get('level', after.get('log.level')) == level
                assert f'level="{level}"' in labels
                assert after['outcome'] == ('error' if code >= 400 else 'success')
                assert after['error_type'] == ('server_error' if code >= 500 else 'client_error' if code >= 400 else 'none')
                assert after['original_level'] == 'INFO'
                assert after['processing_pipeline'] == 'alloy_http_status_v1'
                for key in before.keys() - {'log', 'log.level'}:
                    assert after[key] == before[key], key
            print(f'PASS: {len(inputs)} Alloy samples; body, labels, boundaries and unchanged fields verified.')
        finally:
            subprocess.run(['docker', 'rm', '-f', name], stdout=subprocess.DEVNULL, check=False)


if __name__ == '__main__':
    main()
