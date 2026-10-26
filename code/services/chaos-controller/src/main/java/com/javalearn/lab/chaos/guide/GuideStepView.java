package com.javalearn.lab.chaos.guide;

/**
 * One scenario step as the console sees it.
 *
 * <p>The cursor in {@link GuideStateView#currentStep()} indexes this record, so
 * the console renders the explanation of the step it is about to execute without
 * re-reading the scenario file.</p>
 *
 * @param atSeconds timeline offset of the event
 * @param action    kill|start|restart|freeze|resume
 * @param target    lab container the action is applied to
 * @param ttlSeconds auto-revert delay, {@code null} for one-shot actions
 * @param title     short label, {@code null} when the scenario omits it
 * @param text      one-line description, {@code null} when the scenario omits it
 * @param explain   现象/根因/处方/预期曲线 block, {@code null} when absent
 */
public record GuideStepView(double atSeconds,
                            String action,
                            String target,
                            Integer ttlSeconds,
                            String title,
                            String text,
                            GuideStepExplain explain) {

    static GuideStepView of(GuideEvent event) {
        return new GuideStepView(event.atSeconds(), event.action(), event.target(), event.ttlSeconds(),
                event.title(), event.text(), event.explain());
    }
}
