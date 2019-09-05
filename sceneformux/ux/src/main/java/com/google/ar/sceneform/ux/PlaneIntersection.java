package com.google.ar.sceneform.ux;

import androidx.annotation.Nullable;

import com.google.ar.core.Plane;
import com.google.ar.core.Pose;
import com.google.ar.sceneform.collision.Ray;
import com.google.ar.sceneform.math.Vector3;

public class PlaneIntersection {
    public static @Nullable
    Pose intersect(Plane plane, Ray ray, boolean isInfinite) {
        Vector3 normalizedDirection = ray.getDirection().normalized();
        Pose planeCenterPose = plane.getCenterPose();
        float[] planeYAxis = planeCenterPose.getYAxis();
        float[] planeTranslation = planeCenterPose.getTranslation();
        Vector3 rayOrigin = ray.getOrigin();
        Vector3 normalizedPlaneNormal = new Vector3(planeYAxis[0], planeYAxis[1], planeYAxis[2]);
        float d = Vector3.dot(normalizedDirection, normalizedPlaneNormal);
        float n = Vector3.dot(Vector3.subtract(new Vector3(planeTranslation[0], planeTranslation[1], planeTranslation[2]), rayOrigin), normalizedPlaneNormal);

        if (d == 0f) {
            if (n == 0f) {
                return new Pose(new float[]{rayOrigin.x, rayOrigin.y, rayOrigin.z}, planeCenterPose.getRotationQuaternion());
            } else {
                return null;
            }
        }

        float distance = n / d;

        if (distance < 0f) {
            return null;
        }

        Vector3 position = Vector3.add(rayOrigin, normalizedDirection.scaled(distance));
        Pose pose = new Pose(new float[]{position.x, position.y, position.z}, planeCenterPose.getRotationQuaternion());

        if (isInfinite || plane.isPoseInPolygon(pose)) {
            return pose;
        } else {
            return null;
        }
    }
}
