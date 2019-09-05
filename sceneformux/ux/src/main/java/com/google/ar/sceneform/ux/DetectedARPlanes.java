package com.google.ar.sceneform.ux;

import androidx.annotation.Nullable;

import com.google.ar.core.Plane;
import com.google.ar.core.TrackingState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

public class DetectedARPlanes {

    class TypedPlanes {
        private Plane.Type type;
        private Comparator<? super Plane> sortOperator;
        private ArrayList<Plane> sortedPlanes = new ArrayList<>();

        TypedPlanes(Plane.Type type, Comparator<? super Plane> sortOperator) {
            this.type = type;
            this.sortOperator = sortOperator;
        }

        public List<Plane> getPlanes() {
            return sortedPlanes;
        }


        @Nullable Plane getFirstPlane() {
            return sortedPlanes.isEmpty() ? null : sortedPlanes.get(0);
        }

        Boolean isFirstPlane(Plane plane) {
            return plane == getFirstPlane();
        }

        Boolean isEmpty() {
            return sortedPlanes.isEmpty();
        }

        void update(Collection<Plane> planes) {
            boolean sortAfterInsertion = false;

            for (Plane plane : planes) {
                if (plane.getType() == type) {
                    Plane topPlane = plane.getSubsumedBy();
                    if (topPlane==null) {
                        topPlane = plane;
                    }
                    TrackingState state = plane.getTrackingState();
                    switch (state) {
                        case TRACKING: {
                            if (!topPlane.equals(plane)) {
                                sortedPlanes.remove(plane);
                            }
                            if (!sortedPlanes.contains(topPlane)) {
                                sortedPlanes.add(topPlane);
                                sortAfterInsertion = true;
                            }
                            break;
                        }
                        case STOPPED: {
                            sortedPlanes.remove(topPlane);
                            break;
                        }
                    }
                }
            }

            if (sortAfterInsertion) {
                sortedPlanes.sort(sortOperator);
            }
        }
    }

    Comparator<? super Plane> c = (Plane plane1, Plane plane2) -> {
        float d = plane1.getCenterPose().ty() - plane2.getCenterPose().ty();
        return d != 0f ? (d < 0 ? -1 : 1) : 0;
    };

    TypedPlanes floorPlanes = new TypedPlanes(Plane.Type.HORIZONTAL_UPWARD_FACING, (Comparator<? super Plane>)((Plane plane1, Plane plane2) -> {
        float d = plane1.getCenterPose().ty() - plane2.getCenterPose().ty();
        return d != 0f ? (d < 0 ? -1 : 1) : 0;
    }));
    TypedPlanes ceilPlanes = new TypedPlanes(Plane.Type.HORIZONTAL_DOWNWARD_FACING, (Comparator<? super Plane>)((Plane plane1, Plane plane2) -> {
        float d = plane2.getCenterPose().ty() - plane1.getCenterPose().ty();
        return d != 0f ? (d < 0 ? -1 : 1) : 0;
    }));
    TypedPlanes wallPlanes = new TypedPlanes(Plane.Type.VERTICAL, (Comparator<? super Plane>)((Plane plane1, Plane plane2) -> {
        float d = plane1.getCenterPose().ty() - plane2.getCenterPose().ty();
        return d != 0f ? (d < 0 ? -1 : 1) : 0;
    }));

    public void update(Collection<Plane> planes) {
        floorPlanes.update(planes);
        // Uncomment when types are supported
        //ceilPlanes.update(planes)
        //wallPlanes.update(planes)
    }
}
