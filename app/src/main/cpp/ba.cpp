#include <jni.h>
#include <ceres/ceres.h>
#include <thread>

namespace {
struct Reproj {
    double u, v, fx, fy, cx, cy;
    template <typename T>
    bool operator()(const T* cam, const T* X, T* r) const {
        T p[3];
        ceres::AngleAxisRotatePoint(cam, X, p);
        p[0] += cam[3]; p[1] += cam[4]; p[2] += cam[5];
        T z = ceres::fmax(p[2], T(1e-6));
        r[0] = T(fx) * p[0] / z + T(cx) - T(u);
        r[1] = T(fy) * p[1] / z + T(cy) - T(v);
        return true;
    }
};
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_photogrammetry_sfm_BundleAdjuster_solve(
        JNIEnv* env, jobject,
        jintArray jCamIds, jintArray jPtIds, jdoubleArray jUv,
        jdoubleArray jCams, jdoubleArray jPts, jdoubleArray jK,
        jint nObs, jint nCams, jint nPts,
        jdoubleArray jOutCams, jdoubleArray jOutPts) {

    auto camIds = env->GetIntArrayElements(jCamIds, nullptr);
    auto ptIds  = env->GetIntArrayElements(jPtIds, nullptr);
    auto uv     = env->GetDoubleArrayElements(jUv, nullptr);
    auto cams   = env->GetDoubleArrayElements(jCams, nullptr);
    auto pts    = env->GetDoubleArrayElements(jPts, nullptr);
    auto K      = env->GetDoubleArrayElements(jK, nullptr);

    ceres::Problem problem;
    std::vector<double*> camBlocks(nCams), ptBlocks(nPts);
    for (int c = 0; c < nCams; ++c) {
        camBlocks[c] = cams + 6 * c;
        problem.AddParameterBlock(camBlocks[c], 6);
    }
    for (int p = 0; p < nPts; ++p) {
        ptBlocks[p] = pts + 3 * p;
        problem.AddParameterBlock(ptBlocks[p], 3);
    }
    for (int i = 0; i < nObs; ++i) {
        const double* k = K + 4 * camIds[i];
        problem.AddResidualBlock(
            new ceres::AutoDiffCostFunction<Reproj, 2, 6, 3>(
                new Reproj{uv[2*i], uv[2*i+1], k[0], k[1], k[2], k[3]}),
            new ceres::HuberLoss(1.0),
            camBlocks[camIds[i]], ptBlocks[ptIds[i]]);
    }
    problem.SetParameterBlockConstant(camBlocks[0]);
    for (int c = 1; c < nCams; ++c)
        if (!problem.HasResidualBlockForParameterBlock(camBlocks[c]))
            problem.SetParameterBlockConstant(camBlocks[c]);

    ceres::Solver::Options o;
    o.linear_solver_type = ceres::DENSE_SCHUR;
    o.max_num_iterations = 50;
    o.num_threads = (int) std::thread::hardware_concurrency();
    ceres::Solver::Summary s;
    ceres::Solve(o, &problem, &s);

    env->SetDoubleArrayRegion(jOutCams, 0, nCams * 6, cams);
    env->SetDoubleArrayRegion(jOutPts, 0, nPts * 3, pts);

    env->ReleaseIntArrayElements(jCamIds, camIds, JNI_ABORT);
    env->ReleaseIntArrayElements(jPtIds, ptIds, JNI_ABORT);
    env->ReleaseDoubleArrayElements(jUv, uv, JNI_ABORT);
    env->ReleaseDoubleArrayElements(jCams, cams, JNI_ABORT);
    env->ReleaseDoubleArrayElements(jPts, pts, JNI_ABORT);
    env->ReleaseDoubleArrayElements(jK, K, JNI_ABORT);
    return true;
}
