package android.arch.lifecycle;

/**
 * 桩实现：GeckoRuntime.init() 会调用
 * ProcessLifecycleOwner.get().getLifecycle().addObserver(new LifecycleListener())
 * 这里把它变成 no-op —— Gecko 收不到 ON_PAUSE/ON_STOP，不会在退到后台时自杀。
 */
public final class ProcessLifecycleOwner implements LifecycleOwner {

    private static final ProcessLifecycleOwner INSTANCE = new ProcessLifecycleOwner();

    private final Lifecycle lifecycle = new Lifecycle() {
        @Override
        public void addObserver(LifecycleObserver observer) {
            // no-op：不派发生命周期事件
        }

        @Override
        public void removeObserver(LifecycleObserver observer) {
            // no-op
        }

        @Override
        public State getCurrentState() {
            return State.RESUMED;
        }
    };

    private ProcessLifecycleOwner() {
    }

    public static LifecycleOwner get() {
        return INSTANCE;
    }

    @Override
    public Lifecycle getLifecycle() {
        return lifecycle;
    }
}
