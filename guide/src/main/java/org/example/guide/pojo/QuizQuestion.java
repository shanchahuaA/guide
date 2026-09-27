package org.example.guide.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 一道四选一题目（#42）。**题库里才有的完整形态** —— 正确项与解析都在这里。
 *
 * <p>它只存在于服务端的题目缓存（{@code cache/QuizBankCache}）与判题过程里，
 * **绝不下发给小程序**：{@code /api/teach/quiz/next} 只吐 {@code id / stem / options}，
 * 答对与否由后端比对 {@link #answerIndex}，把答案下发等于把连对白送（抓包即可刷级）。
 *
 * <p>{@link #id} 是题目在本题库内的序号（0 起）。它是抽题与"本周期内已答对的题不再出"
 * 的判据，也是小程序答题时要原样回传的那个"题号"。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuizQuestion {

    /** 题号（本题库内 0 起），答题时原样回传 */
    private Integer id;

    /** 题干 */
    private String stem;

    /** 四个选项 */
    private List<String> options;

    /** 正确项下标（0-3） */
    private Integer answerIndex;

    /** 解析 */
    private String explanation;
}
