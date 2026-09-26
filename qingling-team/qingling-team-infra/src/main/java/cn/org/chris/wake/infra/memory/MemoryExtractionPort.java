package cn.org.chris.wake.infra.memory;

import java.io.IOException;
import java.util.List;

/**
 * 隔离摘要提取与向量服务的最小端口，便于索引器执行无网络测试。
 */
public interface MemoryExtractionPort {

    /** 提取一句话摘要和领域标签。 */
    MemoryExtractionClient.Extraction extract(String userMessage, String assistantReply)
            throws IOException, InterruptedException;

    /** 为输入文本批量生成固定维度向量。 */
    List<float[]> embed(List<String> texts) throws IOException, InterruptedException;
}
