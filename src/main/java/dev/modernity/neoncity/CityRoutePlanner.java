package dev.modernity.neoncity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.PriorityQueue;

/** Pure shortest-path planner over the seeded Project Moon transport graph. */
public final class CityRoutePlanner {
    private static final int CURVE_SEGMENTS = 24;

    private CityRoutePlanner() {
    }

    public static Route shortest(
            MegacityLayout layout,
            double startX,
            double startZ,
            double targetX,
            double targetZ) {
        MegacityLayout.Location startLocation = layout.locateDistrict(
                (int) Math.round(startX), (int) Math.round(startZ));
        MegacityLayout.Location targetLocation = layout.locateDistrict(
                (int) Math.round(targetX), (int) Math.round(targetZ));
        MegacityLayout.Node start = startLocation.primary();
        MegacityLayout.Node target = targetLocation.primary();
        if (!startLocation.insideCity() || !targetLocation.insideCity()) {
            List<Point> points = List.of(
                    new Point(startX, startZ),
                    new Point(targetX, targetZ));
            return new Route(points, List.of(), polylineLength(points));
        }
        if (start.district() == target.district()) {
            List<Point> points = List.of(
                    new Point(startX, startZ),
                    new Point(targetX, targetZ));
            return new Route(points, List.of(start.district()), polylineLength(points));
        }

        EnumMap<District, Double> distances = new EnumMap<>(District.class);
        EnumMap<District, Previous> previous = new EnumMap<>(District.class);
        for (District district : District.values()) {
            distances.put(district, Double.POSITIVE_INFINITY);
        }
        distances.put(start.district(), 0.0);

        PriorityQueue<SearchEntry> open = new PriorityQueue<>(
                Comparator.comparingDouble(SearchEntry::distance)
                        .thenComparingInt(entry -> entry.district().ordinal()));
        open.add(new SearchEntry(start.district(), 0.0));
        while (!open.isEmpty()) {
            SearchEntry current = open.remove();
            if (current.distance() > distances.get(current.district())) continue;
            if (current.district() == target.district()) break;
            for (MegacityLayout.Edge edge : layout.edges()) {
                District next = adjacent(edge, current.district());
                if (next == null) continue;
                double candidate = current.distance() + curveLength(edge);
                if (candidate + 1.0E-9 < distances.get(next)) {
                    distances.put(next, candidate);
                    previous.put(next, new Previous(current.district(), edge));
                    open.add(new SearchEntry(next, candidate));
                }
            }
        }

        if (!previous.containsKey(target.district())) {
            List<Point> points = List.of(
                    new Point(startX, startZ),
                    new Point(targetX, targetZ));
            return new Route(points, List.of(start.district(), target.district()),
                    polylineLength(points));
        }

        ArrayList<Previous> reversedEdges = new ArrayList<>();
        ArrayList<District> reversedDistricts = new ArrayList<>();
        District cursor = target.district();
        reversedDistricts.add(cursor);
        while (cursor != start.district()) {
            Previous step = previous.get(cursor);
            reversedEdges.add(step);
            cursor = step.from();
            reversedDistricts.add(cursor);
        }
        Collections.reverse(reversedEdges);
        Collections.reverse(reversedDistricts);

        ArrayList<Point> points = new ArrayList<>();
        addDistinct(points, new Point(startX, startZ));
        addDistinct(points, point(start));
        District from = start.district();
        for (Previous step : reversedEdges) {
            appendCurve(points, step.edge(), from);
            from = adjacent(step.edge(), from);
        }
        addDistinct(points, point(target));
        addDistinct(points, new Point(targetX, targetZ));
        return new Route(points, reversedDistricts, polylineLength(points));
    }

    private static District adjacent(MegacityLayout.Edge edge, District district) {
        if (edge.first().district() == district) return edge.second().district();
        if (edge.second().district() == district) return edge.first().district();
        return null;
    }

    private static void appendCurve(
            List<Point> points, MegacityLayout.Edge edge, District from) {
        boolean forward = edge.first().district() == from;
        for (int step = 0; step <= CURVE_SEGMENTS; step++) {
            double fraction = step / (double) CURVE_SEGMENTS;
            addDistinct(points, curvePoint(edge, forward ? fraction : 1.0 - fraction));
        }
    }

    private static double curveLength(MegacityLayout.Edge edge) {
        Point previous = curvePoint(edge, 0.0);
        double length = 0.0;
        for (int step = 1; step <= CURVE_SEGMENTS; step++) {
            Point current = curvePoint(edge, step / (double) CURVE_SEGMENTS);
            length += distance(previous, current);
            previous = current;
        }
        return length;
    }

    private static Point curvePoint(MegacityLayout.Edge edge, double fraction) {
        double controlX = (edge.first().x() + edge.second().x()) * 0.5
                - (edge.second().z() - edge.first().z()) * edge.bend();
        double controlZ = (edge.first().z() + edge.second().z()) * 0.5
                + (edge.second().x() - edge.first().x()) * edge.bend();
        double inverse = 1.0 - fraction;
        return new Point(
                inverse * inverse * edge.first().x()
                        + 2.0 * inverse * fraction * controlX
                        + fraction * fraction * edge.second().x(),
                inverse * inverse * edge.first().z()
                        + 2.0 * inverse * fraction * controlZ
                        + fraction * fraction * edge.second().z());
    }

    private static Point point(MegacityLayout.Node node) {
        return new Point(node.x(), node.z());
    }

    private static void addDistinct(List<Point> points, Point point) {
        if (points.isEmpty() || distance(points.getLast(), point) > 0.01) {
            points.add(point);
        }
    }

    private static double polylineLength(List<Point> points) {
        double length = 0.0;
        for (int index = 1; index < points.size(); index++) {
            length += distance(points.get(index - 1), points.get(index));
        }
        return length;
    }

    private static double distance(Point first, Point second) {
        return Math.hypot(first.x() - second.x(), first.z() - second.z());
    }

    public record Point(double x, double z) {
    }

    public record Route(List<Point> points, List<District> districts, double length) {
        public Route {
            points = List.copyOf(points);
            districts = List.copyOf(districts);
        }

        public boolean isEmpty() {
            return points.isEmpty();
        }
    }

    private record SearchEntry(District district, double distance) {
    }

    private record Previous(District from, MegacityLayout.Edge edge) {
    }
}
