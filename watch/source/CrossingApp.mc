import Toybox.Application;
import Toybox.Communications;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.Timer;
import Toybox.WatchUi;

class CrossingApp extends Application.AppBase {
    private const STALL_MS = 5000;

    var sending = true;
    var received = 0;
    var sent = 0;
    var failed = 0;
    var draws = 0;
    var lastDraw = 0;
    private var _busy = false;
    private var _stalled = false;
    private var _timer = new Timer.Timer();

    function initialize() {
        AppBase.initialize();
    }

    function onStart(state as Dictionary?) as Void {
        lastDraw = System.getTimer();
        Communications.registerForPhoneAppMessages(method(:onPhone));
        _timer.start(method(:onTick), 1000, true);
        log("start");
    }

    function onStop(state as Dictionary?) as Void {
        log("stop");
    }

    function getInitialView() as [WatchUi.Views] or [WatchUi.Views, WatchUi.InputDelegates] {
        return [new CrossingView(self), new CrossingDelegate(self)];
    }

    function onPhone(message as Communications.PhoneAppMessage) as Void {
        received++;
    }

    function onTick() as Void {
        if (sending && !_busy) {
            _busy = true;
            var status = { "received" => received, "draws" => draws, "sent" => sent, "failed" => failed };
            Communications.transmit(status, null, new SendListener(self));
        }
        var idle = System.getTimer() - lastDraw;
        if (idle > STALL_MS && !_stalled) {
            _stalled = true;
            log("onUpdate not called for " + idle / 1000 + " s");
        } else if (idle <= STALL_MS && _stalled) {
            _stalled = false;
            log("onUpdate called again");
        }
        WatchUi.requestUpdate();
    }

    function onSent(ok as Boolean) as Void {
        _busy = false;
        if (ok) {
            sent++;
        } else {
            failed++;
        }
    }

    function onDraw() as Void {
        draws++;
        lastDraw = System.getTimer();
    }

    function toggle() as Void {
        sending = !sending;
        log(sending ? "watch sending on" : "watch sending off");
    }

    function log(event as String) as Void {
        var time = System.getClockTime();
        System.println(time.hour.format("%02d") + ":" + time.min.format("%02d") + ":" + time.sec.format("%02d")
            + " " + event + ": received " + received + ", draws " + draws + ", sent " + sent + ", failed " + failed);
    }
}

class SendListener extends Communications.ConnectionListener {
    private var _app as CrossingApp;

    function initialize(app as CrossingApp) {
        ConnectionListener.initialize();
        _app = app;
    }

    function onComplete() as Void {
        _app.onSent(true);
    }

    function onError() as Void {
        _app.onSent(false);
    }
}

class CrossingDelegate extends WatchUi.BehaviorDelegate {
    private var _app as CrossingApp;

    function initialize(app as CrossingApp) {
        BehaviorDelegate.initialize();
        _app = app;
    }

    function onSelect() as Boolean {
        _app.toggle();
        return true;
    }
}

class CrossingView extends WatchUi.View {
    private var _app as CrossingApp;

    function initialize(app as CrossingApp) {
        View.initialize();
        _app = app;
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        _app.onDraw();
        var lines = [
            "received " + _app.received,
            "sent " + _app.sent,
            "failed " + _app.failed,
            "draws " + _app.draws,
            _app.sending ? "both send" : "phone only"
        ];
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        var y = 40;
        for (var i = 0; i < lines.size(); i++) {
            dc.drawText(dc.getWidth() / 2, y, Graphics.FONT_XTINY, lines[i], Graphics.TEXT_JUSTIFY_CENTER);
            y += 22;
        }
    }
}
