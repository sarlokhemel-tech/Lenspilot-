import sys, types, importlib.abc, importlib.machinery
from unittest import mock
class Finder(importlib.abc.MetaPathFinder, importlib.abc.Loader):
    BAD=('firebase_admin','google','flask_cors','groq','requests','stripe','openai','PIL','numpy','dotenv','docx','pypdf','fitz','bs4','yt_dlp','youtube_transcript_api','cv2','pydub','edge_tts','gunicorn','certifi','cryptography','jwt','markdown','reportlab','pptx','openpyxl','tiktoken','httpx','aiohttp','psycopg2','redis','boto3')
    def find_spec(self, name, path, target=None):
        top=name.split('.')[0]
        if top in self.BAD:
            return importlib.machinery.ModuleSpec(name,self,is_package=True)
    def create_module(self, spec):
        m=mock.MagicMock(name=spec.name); m.__path__=[]; m.__spec__=spec; m.__name__=spec.name; return m
    def exec_module(self, m): pass
sys.meta_path.insert(0,Finder())
import importlib.util
spec=importlib.util.spec_from_file_location('app167','../app167.py')
m=importlib.util.module_from_spec(spec)
sys.modules['app167']=m
spec.loader.exec_module(m)
print('imported ok; flask app:',type(m.app).__name__, 'solo', m._DUO_SOLO, sorted(m._DUO_SYSTEMS))
