package org.broadinstitute.dsm.model.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import lombok.Setter;
import org.broadinstitute.dsm.db.dao.kit.KitDao;
import org.broadinstitute.dsm.route.kit.KitPayload;
import org.broadinstitute.dsm.route.kit.ScanPayload;

@Setter
public abstract class BaseKitUseCase implements Supplier<List<ScanError>> {

    protected KitPayload kitPayload;
    protected KitDao kitDao;

    private BaseKitUseCase decoratedScanUseCase;

    public BaseKitUseCase(KitPayload kitPayload, KitDao kitDao) {
        this.kitPayload = kitPayload;
        this.kitDao = kitDao;
    }

    @Override
    public List<ScanError> get() {
        List<ScanError> result = new ArrayList<>();
        for (ScanPayload scanPayload : kitPayload.getScanPayloads()) {
            process(scanPayload).ifPresent(result::add);
        }
        return result;
    }

    protected abstract Optional<ScanError> process(ScanPayload scanPayload);

    protected boolean isKitUpdateSuccessful(Optional<ScanError> maybeScanError) {
        return maybeScanError.isEmpty();
    }

    protected BaseKitUseCase getDecoratedScanUseCase() {
        if (Objects.isNull(decoratedScanUseCase)) {
            decoratedScanUseCase = new NullObjectDecorator();
        }
        return decoratedScanUseCase;
    }
}