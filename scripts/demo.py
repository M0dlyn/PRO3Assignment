#!/usr/bin/env python3
"""Exercise the entire running system; uses only Python's standard library."""
import datetime
import json
import os
import time
import urllib.error
import urllib.request
import uuid

PREFIX = 'DEMO-' + uuid.uuid4().hex[:8]
TODAY = datetime.datetime.now(datetime.timezone.utc).date().isoformat()
PORTS = {'receiving': 8081, 'cutting': 8082, 'packing': 8083, 'central': 8084}


def request(role, path, payload=None):
    headers = {}
    token = os.environ.get('RECALL_TOKEN', 'local-demo-recall') if role == 'central' else os.environ.get('STATION_TOKEN', '')
    if token:
        headers['Authorization'] = 'Bearer ' + token
    body = None
    if payload is not None:
        body = json.dumps(payload).encode()
        headers['Content-Type'] = 'application/json'
    req = urllib.request.Request(f'http://127.0.0.1:{PORTS[role]}/api/{path}', body, headers)
    with urllib.request.urlopen(req, timeout=5) as response:
        return json.load(response)


def await_record(role, path):
    for _ in range(100):
        try:
            return request(role, path)
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
        except urllib.error.URLError:
            pass
        time.sleep(.2)
    raise RuntimeError(f'Timed out waiting for {role}/{path}; inspect /api/status and service logs')


def main():
    # Wait for startup; the script can be invoked immediately after the launcher.
    for role in PORTS:
        await_record(role, 'status')
    animal_a, animal_b = PREFIX + '-A', PREFIX + '-B'
    for animal in (animal_a, animal_b):
        request('receiving', 'animals', {'registrationNumber': animal, 'arrivalDate': TODAY,
                'arrivalWeightKg': 120, 'origin': 'Demo Farm', 'species': 'pig'})
        await_record('cutting', 'animals/' + animal)
    for kind, capacity in [('leg', 30), ('loin', 10)]:
        request('cutting', 'trays', {'trayId': PREFIX + '-T-' + kind, 'maximumWeightKg': capacity})
        request('cutting', 'batches', {'batchId': PREFIX + '-B-' + kind, 'trayId': PREFIX + '-T-' + kind, 'partType': kind})
    for suffix, animal, kind, weight in [('L1', animal_a, 'leg', 8), ('L2', animal_b, 'leg', 9),
                                         ('L3', animal_a, 'leg', 8), ('LOIN', animal_a, 'loin', 5)]:
        request('cutting', 'parts', {'partId': PREFIX + '-' + suffix, 'animalRegistrationNumber': animal,
                'batchId': PREFIX + '-B-' + kind, 'partType': kind, 'weightKg': weight})
    for kind in ('leg', 'loin'):
        request('cutting', 'batches/' + PREFIX + '-B-' + kind + '/close', {})
    # Waiting for a batch to exist isn't enough: its close event must also arrive.
    for _ in range(100):
        if all(await_record('packing', 'batches/' + PREFIX + '-B-' + kind)['closed'] for kind in ('leg', 'loin')):
            break
        time.sleep(.2)
    else:
        raise RuntimeError('Batch closure did not synchronize')
    mixed, legs = PREFIX + '-mixed', PREFIX + '-legs'
    request('packing', 'product-types', {'name': mixed, 'requirements': [{'partType': 'leg', 'quantity': 1}, {'partType': 'loin', 'quantity': 1}]})
    request('packing', 'product-types', {'name': legs, 'requirements': [{'partType': 'leg', 'quantity': 2}]})
    for suffix, recipe, parts in [('P1', mixed, ['L2', 'LOIN']), ('P2', legs, ['L1', 'L3'])]:
        product = PREFIX + '-' + suffix
        request('packing', 'products', {'productId': product, 'productType': recipe, 'packedDate': TODAY,
                'partIds': [PREFIX + '-' + part for part in parts]})
        await_record('central', 'products/' + product)
    print('Receiving → cutting → packing → central synchronization succeeded.')
    print('Product:', PREFIX + '-P1', '| Animals:', animal_a, animal_b)
    print('Both products share the leg batch: a conservative recall of either animal returns P1 and P2.')
    print('Run a real gRPC recall:')
    print('RECALL_TOKEN="${RECALL_TOKEN:-local-demo-recall}" java -cp target/traceability-1.0.0.jar dk.via.pro3.TraceabilityClient animal ' + animal_b)


if __name__ == '__main__':
    main()
