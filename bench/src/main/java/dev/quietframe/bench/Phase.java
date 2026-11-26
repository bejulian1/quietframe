package dev.quietframe.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All event timestamps use the client monotonic clock. The half-open window ends independently of presents. */
final class Phase {
    final String name, optionsSha256;
    final long start, end;
    final List<Long> presents = new ArrayList<>();
    final List<WindowSample> ticks = new ArrayList<>();
    int attempts, suboptimal;

    Phase(String name, String optionsSha256, long start, int seconds) {
        this.optionsSha256 = optionsSha256;
        this.name = name;
        this.start = start;
        this.end = start + seconds * 1_000_000_000L;
    }

    void save(Path out) throws IOException {
        Map<String, Object> row = new LinkedHashMap<>();
        double seconds = (end - start) * 1e-9;
        row.put("phase", name);
        row.put("optionsSha256", optionsSha256);
        row.put("startNs", start);
        row.put("endNs", end);
        row.put("presents", presents.size());
        row.put("fps", presents.size() / seconds);
        long[] intervals = new long[Math.max(0, presents.size()-1)];
        try (var f = Files.newBufferedWriter(out.resolve(name + "-frames.csv"))) {
            f.write("presentNs,interPresentMs\n");
            for (int i = 0; i < presents.size(); i++) {
                if (i > 0) intervals[i-1] = presents.get(i) - presents.get(i-1);
                f.write(presents.get(i) + "," + (i == 0 ? "" : intervals[i-1] / 1e6) + "\n");
            }
        }
        Arrays.sort(intervals);
        row.put("p95Ms", intervals.length == 0 ? null : intervals[(int)Math.ceil(.95 * intervals.length)-1] / 1e6);
        row.put("renderAttempts", attempts);
        row.put("suboptimalPresents", suboptimal);
        row.put("ticks", ticks.size());
        row.put("tps", ticks.size() / seconds);
        long prev = start, worst = 0;
        try (var f = Files.newBufferedWriter(out.resolve(name + "-ticks.csv"))) {
            f.write("ns,flags,focused,iconified,hovered,width,height,lastInputMs,idleMs,gain,soundPlaying,playing,lastPresentNs\n");
            for (var s : ticks) {
                worst = Math.max(worst, s.ns()-prev);
                prev = s.ns();
                f.write(s.ns()+","+s.flags()+","+s.focused()+","+s.iconified()+","+s.hovered()+","+s.width()+","+s.height()
                        +","+s.lastInputMs()+","+s.idleMs()+","+(s.gain()==null ? "" : s.gain())+","+s.soundPlaying()
                        +","+s.playing()+","+s.lastPresentNs()+"\n");
            }
        }
        row.put("worstTickGapMs", Math.max(worst, end-prev) / 1e6);
        Observer.json(out.resolve(name + ".json"), row);
    }
}
