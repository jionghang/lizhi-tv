package android.arch.lifecycle;

/**
 * 最小桩：GeckoView 69 编译期依赖 android.arch.lifecycle 1.1.1，
 * 但该 artifact 已从 Google Maven 下架（只剩 androidx）。GeckoView 只用到
 * 这五个符号，且唯一用途是注册"前后台生命周期"回调，因此这里给一个不派发事件的实现：
 * 页面永不收到 ON_PAUSE，正好符合我们"退到后台也别停播"的需求。
 */
public abstract class Lifecycle {

    public enum Event {
        ON_CREATE, ON_START, ON_RESUME, ON_PAUSE, ON_STOP, ON_DESTROY, ON_ANY
    }

    public enum State {
        DESTROYED, INITIALIZED, CREATED, STARTED, RESUMED
    }

    public abstract void addObserver(LifecycleObserver observer);

    public abstract void removeObserver(LifecycleObserver observer);

    public abstract State getCurrentState();
}
