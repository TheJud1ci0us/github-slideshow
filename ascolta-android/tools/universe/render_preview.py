#!/usr/bin/env python3
"""Render the Android renderer's exported meshes and exact GLES shaders with Mesa EGL.
No painted substitute: this compiles the production shaders and uses a real depth buffer.
"""
import ctypes as C, json, math, os, pathlib, sys
os.environ.setdefault('EGL_PLATFORM','surfaceless')
os.environ.setdefault('LIBGL_ALWAYS_SOFTWARE','1')
os.environ.setdefault('MESA_SHADER_CACHE_DISABLE','true')
from PIL import Image
root=pathlib.Path(__file__).resolve().parents[2]
folder=root/'app/build/world-preview'
egl=C.CDLL('libEGL.so.1')
def eglfn(name,restype,args):
 f=getattr(egl,name);f.restype=restype;f.argtypes=args;return f
ptr=C.c_void_p;integer=C.c_int;uint=C.c_uint;fl=C.c_float;boolean=C.c_ubyte
get=eglfn('eglGetDisplay',ptr,[ptr]);display=get(None)
major=integer();minor=integer()
assert eglfn('eglInitialize',uint,[ptr,C.POINTER(integer),C.POINTER(integer)])(display,C.byref(major),C.byref(minor)), 'EGL initialize failed'
assert eglfn('eglBindAPI',uint,[uint])(0x30A0)
attrs=(integer*15)(0x3033,1,0x3040,4,0x3024,8,0x3023,8,0x3022,8,0x3021,8,0x3025,24,0x3038)
config=ptr();count=integer()
assert eglfn('eglChooseConfig',uint,[ptr,C.POINTER(integer),C.POINTER(ptr),integer,C.POINTER(integer)])(display,attrs,C.byref(config),1,C.byref(count)) and count.value
width,height=1236,1425
surface=eglfn('eglCreatePbufferSurface',ptr,[ptr,ptr,C.POINTER(integer)])(display,config,(integer*5)(0x3057,width,0x3056,height,0x3038))
context=eglfn('eglCreateContext',ptr,[ptr,ptr,ptr,C.POINTER(integer)])(display,config,None,(integer*3)(0x3098,2,0x3038))
assert surface and context
assert eglfn('eglMakeCurrent',uint,[ptr,ptr,ptr,ptr])(display,surface,surface,context)
proc=eglfn('eglGetProcAddress',ptr,[C.c_char_p])
def gl(name,result,args):
 p=proc(name.encode());assert p,name
 return C.CFUNCTYPE(result,*args)(p)
create=gl('glCreateShader',uint,[uint]);source=gl('glShaderSource',None,[uint,integer,C.POINTER(C.c_char_p),ptr]);compile=gl('glCompileShader',None,[uint]);shaderiv=gl('glGetShaderiv',None,[uint,uint,C.POINTER(integer)])
log=gl('glGetShaderInfoLog',None,[uint,integer,ptr,C.c_char_p])
def shader(kind,name):
 s=create(kind);data=(root/'app/src/main/assets/universe'/name).read_bytes();raw=C.c_char_p(data);source(s,1,C.byref(raw),None);compile(s)
 ok=integer();shaderiv(s,0x8B81,C.byref(ok))
 if not ok.value:
  text=C.create_string_buffer(4096);log(s,4096,None,text);raise RuntimeError(text.value.decode())
 return s
program=gl('glCreateProgram',uint,[])();attach=gl('glAttachShader',None,[uint,uint]);attach(program,shader(0x8B31,'world.vert'));attach(program,shader(0x8B30,'world.frag'));gl('glLinkProgram',None,[uint])(program)
ok=integer();gl('glGetProgramiv',None,[uint,uint,C.POINTER(integer)])(program,0x8B82,C.byref(ok))
if not ok.value:
 text=C.create_string_buffer(4096);gl('glGetProgramInfoLog',None,[uint,integer,ptr,C.c_char_p])(program,4096,None,text);raise RuntimeError(text.value.decode())
gl('glUseProgram',None,[uint])(program)
uniform=gl('glGetUniformLocation',integer,[uint,C.c_char_p]);attribute=gl('glGetAttribLocation',integer,[uint,C.c_char_p])
u={n:uniform(program,n.encode()) for n in ['uMvp','uModel','uTime','uSea','uEye']}
a=[attribute(program,n.encode()) for n in ['aPosition','aColor','aNormal']]
assert min(list(u.values())+a)>=0
matrix=gl('glUniformMatrix4fv',None,[integer,integer,boolean,C.POINTER(fl)]);one=gl('glUniform1f',None,[integer,fl]);three=gl('glUniform3f',None,[integer,fl,fl,fl])
gen=gl('glGenBuffers',None,[integer,C.POINTER(uint)]);bind=gl('glBindBuffer',None,[uint,uint]);datafn=gl('glBufferData',None,[uint,C.c_ssize_t,ptr,uint]);enableattr=gl('glEnableVertexAttribArray',None,[uint]);attr=gl('glVertexAttribPointer',None,[uint,integer,uint,boolean,integer,ptr]);draw=gl('glDrawArrays',None,[uint,integer,integer])
identity=[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]
gl('glViewport',None,[integer,integer,integer,integer])(0,0,width,height)
gl('glEnable',None,[uint])(0x0B71);gl('glDepthFunc',None,[uint])(0x0203)
gl('glClearColor',None,[fl,fl,fl,fl])(.066,.19,.24,1)
for name in sys.argv[1:] or ['initial','developed','france']:
 fixture=json.loads((folder/f'{name}.json').read_text())
 gl('glClear',None,[uint])(0x4000|0x100)
 matrix(u['uMvp'],1,False,(fl*16)(*fixture['camera']));three(u['uEye'],*fixture['eye']);one(u['uTime'],2)
 def upload(values):
  b=uint();gen(1,C.byref(b));bind(0x8892,b.value);array=(fl*len(values))(*values);datafn(0x8892,C.sizeof(array),array,0x88E4)
  return b.value,len(values)//9
 buffers={key:upload(fixture[key]) for key in ['water','terrain','props','clouds','worker']}
 def render(key,model=identity):
  b,n=buffers[key];bind(0x8892,b);matrix(u['uModel'],1,False,(fl*16)(*model));one(u['uSea'],1 if key=='water' else 0)
  for location,offset in zip(a,[0,12,24]):enableattr(location);attr(location,3,0x1406,False,36,ptr(offset))
  draw(4,0,n)
 render('water');render('terrain');render('props')
 state=json.loads(fixture['state']);homes=fixture['workerHomes']
 for i in range(min(state['workers'],5)):
  if not homes:break
  x,y,z=homes[i%len(homes)];phase=1.2+i*2;c=math.cos(-phase);s=math.sin(-phase)
  model=[c,0,-s,0,0,1,0,0,s,0,c,0,x+math.cos(phase)*.28,y+.03+abs(math.sin(8+i))*.025,z+math.sin(phase)*.25,1]
  render('worker',model)
 render('clouds')
 gl('glFinish',None,[])()
 error=gl('glGetError',uint,[])();assert error==0,hex(error)
 pixels=(C.c_ubyte*(width*height*4))();gl('glReadPixels',None,[integer,integer,integer,integer,uint,uint,ptr])(0,0,width,height,0x1908,0x1401,pixels)
 image=Image.frombytes('RGBA',(width,height),bytes(pixels)).transpose(Image.Transpose.FLIP_TOP_BOTTOM)
 image.save(folder/f'{name}.png')
 print(f'{name}: compiled production shaders; {sum(n for _,n in buffers.values())} vertices; GL_NO_ERROR; {width}x{height}')
gl('glFinish',None,[])()
