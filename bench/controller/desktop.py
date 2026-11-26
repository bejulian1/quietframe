"""Minimal managed X11 windows and EWMH/XTest input on the active desktop."""

import ctypes as C
from ctypes.util import find_library
import os
import re
import shutil
import subprocess
import time

I, U, L, UL, P = C.c_int, C.c_uint, C.c_long, C.c_ulong, C.c_void_p


class ClientMessage(C.Structure):
    _fields_ = [('type', I), ('serial', UL), ('send_event', I), ('display', P),
                ('window', UL), ('message_type', UL), ('format', I), ('data', L * 5)]


class Event(C.Union):
    _fields_ = [('type', I), ('message', ClientMessage), ('pad', L * 24)]


class Unsupported(RuntimeError):
    pass


def bind(lib, name, result, *args):
    func = getattr(lib, name)
    func.restype, func.argtypes = result, args
    return func


def wait(check, timeout=2):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if check():
            return True
        time.sleep(.04)
    return False


class Desktop:
    def __init__(self, backend="xtest"):
        if not os.environ.get('DISPLAY') or not shutil.which('xprop'):
            raise Unsupported('DISPLAY and xprop are required for an Xorg or XWayland desktop')
        self.x = C.CDLL(find_library('X11') or 'libX11.so.6')
        self.test = C.CDLL(find_library('Xtst') or 'libXtst.so.6') if backend == 'xtest' else None
        signatures = {
            'XOpenDisplay': (P, C.c_char_p), 'XCloseDisplay': (I, P),
            'XDefaultRootWindow': (UL, P), 'XDefaultScreen': (I, P),
            'XDisplayWidth': (I, P, I), 'XDisplayHeight': (I, P, I),
            'XCreateSimpleWindow': (UL, P, UL, I, I, U, U, U, UL, UL),
            'XStoreName': (I, P, UL, C.c_char_p), 'XSelectInput': (I, P, UL, L),
            'XMapRaised': (I, P, UL), 'XMoveWindow': (I, P, UL, I, I),
            'XDestroyWindow': (I, P, UL), 'XSync': (I, P, I),
            'XInternAtom': (UL, P, C.c_char_p, I),
            'XSendEvent': (I, P, UL, I, L, C.POINTER(Event)),
            'XIconifyWindow': (I, P, UL, I),
            'XTranslateCoordinates': (I, P, UL, UL, I, I, P, P, P),
            'XQueryPointer': (I, P, UL, P, P, P, P, P, P, P),
            'XKeysymToKeycode': (C.c_ubyte, P, UL),
            'XCheckWindowEvent': (I, P, UL, L, C.POINTER(Event)),
            'XGetGeometry': (I, P, UL, P, P, P, P, P, P, P),
        }
        for name, sig in signatures.items():
            bind(self.x, name, *sig)
        if self.test:
            bind(self.test, 'XTestFakeButtonEvent', I, P, U, I, UL)
            bind(self.test, 'XTestFakeKeyEvent', I, P, U, I, UL)
            bind(self.test, 'XTestFakeMotionEvent', I, P, I, I, I, UL)
            bind(self.test, 'XTestQueryExtension', I, P, P, P, P, P)
        self.d = self.x.XOpenDisplay(None)
        if not self.d:
            raise Unsupported('XOpenDisplay failed')
        dummy = [I() for _ in range(4)]
        if self.test and not self.test.XTestQueryExtension(self.d, *(C.byref(v) for v in dummy)):
            self.x.XCloseDisplay(self.d)
            raise Unsupported('Server does not advertise XTEST')
        self.root = self.x.XDefaultRootWindow(self.d)
        self.screen = self.x.XDefaultScreen(self.d)
        self.width = self.x.XDisplayWidth(self.d, self.screen)
        self.height = self.x.XDisplayHeight(self.d, self.screen)
        self.windows = []
        self.original = self.active()
        child, px, py = self.cursor_over(self.root)
        self.pointer = (px, py) if child else None
        self.mutter = MutterInput(self) if backend == "mutter" else None

    def sync(self):
        self.x.XSync(self.d, 0)

    def prop(self, *args):
        return subprocess.run(['xprop', *args], capture_output=True, text=True, timeout=2).stdout

    def active(self):
        value = re.search(r'0x[0-9a-fA-F]+', self.prop('-root', '_NET_ACTIVE_WINDOW'))
        return int(value[0], 16) if value else 0

    def cursor_over(self, parent):
        children = [UL(), UL()]
        coords = [I() for _ in range(4)]
        mask = U()
        self.x.XQueryPointer(self.d, parent, *(C.byref(x) for x in [*children, *coords, mask]))
        return children[1].value, coords[0].value, coords[1].value

    def cursor(self):
        _, x, y = self.cursor_over(self.root)
        return x, y

    def under_pointer(self, wid):
        parent = self.root
        while parent:
            parent, _, _ = self.cursor_over(parent)
            if parent == wid:
                return True
        return False

    def rect(self, wid):
        child = UL()
        x, y = I(), I()
        w, h, border, depth = U(), U(), U(), U()
        self.x.XGetGeometry(self.d, wid, *(C.byref(v) for v in (child,x,y,w,h,border,depth)))
        self.x.XTranslateCoordinates(self.d, wid, self.root, 0, 0, C.byref(x), C.byref(y), C.byref(child))
        return x.value, y.value, w.value, h.value

    def create(self, title, x, y):
        wid = self.x.XCreateSimpleWindow(self.d, self.root, x, y, 120, 64, 1, 0, 0x34506a)
        self.windows.append(wid)
        self.x.XStoreName(self.d, wid, title.encode())
        self.x.XSelectInput(self.d, wid, 1 | 2)
        self.x.XMapRaised(self.d, wid)
        self.sync()
        wait(lambda: hex(wid) in self.prop('-root', '_NET_CLIENT_LIST'))
        self.x.XMoveWindow(self.d, wid, x, y)
        self.sync()
        return wid

    def place_sink(self, game):
        gx, gy, gw, gh = self.rect(game)
        for x, y in ((12,42), (self.width-140,42), (12,self.height-85), (self.width-140,self.height-85)):
            if x+130 < gx or x > gx+gw or y+72 < gy or y > gy+gh:
                wid = self.create('QuietFrame focus sink', x, y)
                sx, sy, sw, sh = self.rect(wid)
                if sx+sw <= gx or sx >= gx+gw or sy+sh <= gy or sy >= gy+gh:
                    return wid
        raise Unsupported('No visible desktop space for a separate focus sink')

    def activate(self, wid, click=True):
        event = Event()
        event.message = ClientMessage(33, 0, 1, self.d, wid,
            self.x.XInternAtom(self.d, b'_NET_ACTIVE_WINDOW', 0), 32, (L * 5)(2, 0, 0, 0, 0))
        self.x.XMapRaised(self.d, wid)
        self.x.XSendEvent(self.d, self.root, 0, (1 << 20) | (1 << 19), C.byref(event))
        self.sync()
        def focused():
            if self.active() != wid:
                return False
            time.sleep(.12)
            return self.active() == wid
        if wait(focused, .7):
            return
        if click:
            self.point(wid)
            if wait(lambda: self.under_pointer(wid), .6):
                for down in (True, False):
                    if self.mutter:
                        self.mutter.remote('NotifyPointerButton', '(ib)', (274, down))
                    else:
                        self.test.XTestFakeButtonEvent(self.d, 2, down, 0)
                self.sync()
                if wait(focused):
                    return
        raise Unsupported(f'Window manager declined activation of {wid:#x}')

    def motion(self, x, y):
        if self.mutter:
            self.mutter.motion(x, y)
        else:
            self.test.XTestFakeMotionEvent(self.d, self.screen, x, y, 0)
        self.sync()

    def point(self, wid):
        x, y, w, h = self.rect(wid)
        self.motion(x+w//2, y+h//2)

    def move_over(self, wid):
        for _ in range(3):
            self.point(wid)
            if wait(lambda: self.under_pointer(wid), .4):
                time.sleep(.12)
                return
        raise Unsupported(f'Compositor withheld pointer crossing into {wid:#x}')

    def key(self, wid):
        if self.active() != wid:
            raise Unsupported('Focus changed before key injection')
        if self.mutter:
            self.mutter.key()
            return
        code = self.x.XKeysymToKeycode(self.d, 0xffc5)  # F8 is unbound in the staged options.
        if not code:
            raise Unsupported('No F8 keycode in the active XKB layout')
        for down in (1, 0):
            self.test.XTestFakeKeyEvent(self.d, code, down, 0)
        self.sync()

    def minimize(self, wid):
        self.x.XIconifyWindow(self.d, wid, self.screen)
        self.sync()

    def close(self):
        for wid in self.windows:
            self.x.XDestroyWindow(self.d, wid)
        self.sync()
        if self.pointer:
            self.motion(*self.pointer)
        if self.original and hex(self.original) in self.prop('-root', '_NET_CLIENT_LIST'):
            try:
                self.activate(self.original, click=False)
            except Unsupported:
                pass
        if self.mutter:
            self.mutter.close()
        self.x.XCloseDisplay(self.d)


def probe(backend="xtest"):
    d = Desktop(backend)
    try:
        game = d.create('QuietFrame input probe', 280, 240)
        sink = d.place_sink(game)
        d.activate(sink)
        d.point(game)
        x,y,w,h = d.rect(game)
        hover = wait(lambda: d.cursor() == (x+w//2, y+h//2) and d.active() == sink, 1)
        d.activate(game)
        d.key(game)
        received = wait(lambda: d.x.XCheckWindowEvent(d.d, game, 1, C.byref(Event())), 1)
        d.minimize(game)
        iconified = wait(lambda: 'Iconic' in d.prop('-id', hex(game), 'WM_STATE')
                         or '_NET_WM_STATE_HIDDEN' in d.prop('-id', hex(game), '_NET_WM_STATE'))
        d.activate(game)
        return dict(blurHover=hover, keyDelivered=received, iconified=iconified, restored=d.active()==game)
    finally:
        d.close()


class MutterInput:
    """Use Mutter's virtual seat on a single-monitor XWayland session that filters XTEST."""
    REMOTE = 'org.gnome.Mutter.RemoteDesktop'
    CAST = 'org.gnome.Mutter.ScreenCast'

    def __init__(self, desktop):
        from gi.repository import Gio, GLib
        self.b = Gio.bus_get_sync(Gio.BusType.SESSION, None)
        self.variant = GLib.Variant
        self.session = self.call(self.REMOTE, '/org/gnome/Mutter/RemoteDesktop', self.REMOTE, 'CreateSession')[0]
        try:
            sid = self.call(self.REMOTE, self.session, 'org.freedesktop.DBus.Properties', 'Get',
                            '(ss)', (self.REMOTE+'.Session', 'SessionId'))[0]
            cast = self.call(self.CAST, '/org/gnome/Mutter/ScreenCast', self.CAST, 'CreateSession', '(a{sv})',
                             ({'remote-desktop-session-id': GLib.Variant('s',sid)},))[0]
            self.stream = self.call(self.CAST, cast, self.CAST+'.Session', 'RecordMonitor', '(sa{sv})', ('',{}))[0]
            params = self.call(self.CAST, self.stream, 'org.freedesktop.DBus.Properties', 'Get', '(ss)',
                               (self.CAST+'.Stream','Parameters'))[0]
            if tuple(params.get('position', ())) != (0,0) or 'size' not in params:
                raise Unsupported('Mutter input needs the primary monitor at desktop origin')
            self.sx, self.sy = params['size'][0]/desktop.width, params['size'][1]/desktop.height
            active = subprocess.check_output(['xrandr','--listactivemonitors'], text=True)
            if not re.search(r'Monitors:\s*1\b', active):
                raise Unsupported('Mutter coordinate mapping requires one active monitor')
            self.remote('Start')
        except BaseException:
            self.close()
            raise

    def call(self, dest, path, iface, method, sig=None, values=()):
        args = self.variant(sig, values) if sig else None
        return self.b.call_sync(dest, path, iface, method, args, None, 0, 3000, None).unpack()

    def remote(self, method, sig=None, args=()):
        return self.call(self.REMOTE, self.session, self.REMOTE+'.Session', method, sig, args)

    def motion(self,x,y):
        self.remote('NotifyPointerMotionAbsolute','(sdd)',(self.stream,x*self.sx,y*self.sy))

    def key(self):
        for down in (True,False):
            self.remote('NotifyKeyboardKeysym','(ub)',(0xffc5,down))

    def close(self):
        self.remote('Stop')
