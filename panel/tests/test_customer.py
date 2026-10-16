import importlib.util
import os
import tempfile
import uuid
from fastapi.testclient import TestClient

def test_customer_trial_and_isolation():
    with tempfile.TemporaryDirectory() as folder:
        os.environ['DATABASE_PATH'] = folder + '/test.db'
        os.environ['ADMIN_USERNAME'] = 'admin'
        os.environ['ADMIN_PASSWORD'] = 'strong-password'
        spec = importlib.util.spec_from_file_location('sega_test_customer', os.path.join(os.path.dirname(__file__), '..', 'main.py'))
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        client = TestClient(mod.APP)
        def register(mac,key):
            identity={'device_id':str(uuid.uuid4()),'mac_alias':mac,'device_key':key}
            assert client.post('/api/v1/register',json=identity).status_code==200
            return identity
        one=register('02:00:00:00:00:01','ABC123')
        two=register('02:00:00:00:00:02','DEF456')
        assert client.get('/api/v1/config',params={'device_id':one['device_id']},headers={'X-Device-Key':one['device_key']}).json()['active']
        def login(device):
            response=client.post('/customer/api/login',json={'mac_alias':device['mac_alias'],'device_key':device['device_key']})
            assert response.status_code==200
            return {'Authorization':'Bearer '+response.json()['token']}
        h1,h2=login(one),login(two)
        created=client.post('/customer/api/playlists',headers=h1,json={'name':'My list','url':'https://example.org/list.m3u'})
        assert created.status_code==200
        playlist_id=created.json()['id']
        assert len(client.get('/customer/api/playlists',headers=h2).json()['playlists'])==0
        assert client.put(f'/customer/api/playlists/{playlist_id}',headers=h2,json={'name':'bad','url':'https://example.org/a.m3u'}).status_code==404
        assert client.get('/api/v1/config',params={'device_id':one['device_id']},headers={'X-Device-Key':one['device_key']}).json()['playlists']
        with mod.database() as db:
            db.execute('UPDATE devices SET expires_at=? WHERE id=?',('2020-01-01',one['device_id']))
        config=client.get('/api/v1/config',params={'device_id':one['device_id']},headers={'X-Device-Key':one['device_key']}).json()
        assert not config['active'] and config['playlists']==[]
        assert len(client.get('/customer/api/playlists',headers=h1).json()['playlists'])==1
        assert client.post('/admin/api/playlists',auth=('admin','strong-password'),json={'name':'x','url':'https://example.org/a.m3u'}).status_code==404
