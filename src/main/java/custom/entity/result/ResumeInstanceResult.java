package custom.entity.result;

import lombok.Builder;
import lombok.Data;

@Data
@Builder

public class ResumeInstanceResult {
    CommonResult commonResult;
    int costSeconds;
}