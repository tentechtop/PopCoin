package com.pop.popcoinsystem.application.service.wallet;


import com.pop.popcoinsystem.application.service.wallet.vo.BuildWalletUTXODTO;
import com.pop.popcoinsystem.application.service.wallet.vo.TransferVO;
import com.pop.popcoinsystem.application.service.wallet.vo.WalletBalanceVO;
import com.pop.popcoinsystem.data.enums.SigHashType;
import com.pop.popcoinsystem.data.script.*;
import com.pop.popcoinsystem.storage.UTXOSearch;
import com.pop.popcoinsystem.data.transaction.*;
import com.pop.popcoinsystem.data.transaction.dto.TransactionDTO;
import com.pop.popcoinsystem.data.vo.result.PageResult;
import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.exception.InsufficientFundsException;
import com.pop.popcoinsystem.exception.UnsupportedAddressException;
import com.pop.popcoinsystem.service.BlockChainService;
import com.pop.popcoinsystem.util.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import static com.pop.popcoinsystem.storage.POPStorage.getUTXOKey;
import static com.pop.popcoinsystem.service.BlockChainService.convertTransactionDTO;

@Slf4j
@Service
public class WalletService {

    @Resource
    private BlockChainService blockChainService;


    public static Wallet walleta;
    public static Wallet walletb;


    public static void main(String[] args) {
        //测试密码加密私钥
        String password = "123456";
        KeyPair keyPairP = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey = keyPairP.getPrivate();
        PublicKey publicKey = keyPairP.getPublic();

        // 使用密码加密私钥
        String encryptedPrivateKey = CryptoUtil.encryptWithPassword(
                privateKey.getEncoded(),
                password
        );
        log.info("privateKey: {}", CryptoUtil.bytesToHex(privateKey.getEncoded()));
        log.info("encryptedPrivateKey: {}", encryptedPrivateKey);
        //解密
        byte[] bytes = CryptoUtil.decryptWithPassword(
                encryptedPrivateKey,
                password
        );
        log.info("privateKey: {}", CryptoUtil.bytesToHex(bytes));


        // 16  24  32   只能是这些长度
        // 12  18  24
        //byte[] initialEntropy = new byte[16];
        // 生成256位熵（32字节），对应24个助记词
        byte[] initialEntropy = new byte[32];
        SecureRandomUtils.secureRandom().nextBytes(initialEntropy);
        String mnemonic = MnemonicUtils.generateMnemonic(initialEntropy);//12、18、24
        log.info("mnemonic: {}", mnemonic);

        log.info("mnemonic: {}", mnemonic.split(" ").length);


        String myMn = "upon way fade raven dose fetch master drive brief size skin verify utility upon seat wait behave enhance phrase tag vanish square release false";

        byte[] seed = MnemonicUtils.generateSeed(myMn, null); //可以有密码 也可以没有密码


        KeyPair keyPair = CryptoUtil.ECDSASigner.generateKeyPairFromSeed(CryptoUtil.applySHA256(seed));
        PublicKey aPublic = keyPair.getPublic();
        PrivateKey aPrivate = keyPair.getPrivate();
        log.info("publicKey: {}", aPublic);
        log.info("privateKey: {}", aPrivate);
        log.info("mnemonic: {}", mnemonic);
        log.info("seed: {}", seed);
        log.info("publicKey: {}", CryptoUtil.bytesToHex(keyPair.getPublic().getEncoded()));
        log.info("privateKey: {}", CryptoUtil.bytesToHex(keyPair.getPrivate().getEncoded()));


        byte[] txToSign = new byte[32]; //待签名的交易哈希（需按比特币规则生成待签名数据）
        Arrays.fill(txToSign, (byte)0x01);

        byte[] signature = CryptoUtil.ECDSASigner.applySignature(keyPair.getPrivate(), txToSign);

        boolean b = CryptoUtil.ECDSASigner.verifySignature(keyPair.getPublic(), txToSign, signature);
        log.info("signature: {}",b);




        // 4. 生成根私钥和根链码（BIP-32）
        byte[] hmacResult = CryptoUtil.hmacSha512("Bitcoin seed".getBytes(), seed);
        byte[] masterPrivKeyBytes = Arrays.copyOfRange(hmacResult, 0, 32); // 根私钥（32字节）
        byte[] masterChainCode = Arrays.copyOfRange(hmacResult, 32, 64); // 根链码
        // 5. 派生默认路径子私钥（如以太坊：m/44'/60'/0'/0/0）
        List<Integer> defaultPath = Arrays.asList(
                44 | 0x80000000,  // 44'（强化派生）
                60 | 0x80000000,  // 60'（以太坊币种）
                0 | 0x80000000,   // 0'（账户0）
                0,                // 0（外部链）
                2                 // 0（第一个地址）
        );
        PrivateKey childPrivKey = CryptoUtil.deriveChildPrivateKey(masterPrivKeyBytes, masterChainCode, defaultPath);

        // 6. 生成公钥和地址
        PublicKey pubKey = CryptoUtil.derivePublicKey(childPrivKey);
        String pubKeyHex = CryptoUtil.bytesToHex(pubKey.getEncoded());
        String address = CryptoUtil.ECDSASigner.createP2PKHAddressByPK(pubKey.getEncoded()); // 实现地址生成方法
        log.info("address: {}", address);


        byte[] signature2 = CryptoUtil.ECDSASigner.applySignature(childPrivKey, txToSign);

        boolean b2 = CryptoUtil.ECDSASigner.verifySignature(pubKey, txToSign, signature2);
        log.info("signature: {}",b2);


        ///m/44'/60'/0'/0/0：以太坊主账户的第一个接收地址
        //m/44'/60'/0'/0/1：以太坊主账户的第二个接收地址
        //m/44'/60'/0'/1/0：以太坊主账户的第一个 Change 地址（用于找零）



        //而是采用按需派生和 ** 间隙检测（Gap Limit）** 的策略。以下是详细解释：

    }



    public Result<Wallet> createWallet(WalletVO walletVO) {
        Wallet wallet = null;
        if (walletVO.getWalletType()==WalletType.COMMON.getValue()){
            wallet = createNormalWallet(walletVO);
        }else if (walletVO.getWalletType()==WalletType.PASSWORD.getValue()){
            wallet = createPasswordWallet(walletVO);
        }else if (walletVO.getWalletType()==WalletType.MNEMONIC.getValue()){
            wallet = createMnemonicWallet(walletVO);
        }else if (walletVO.getWalletType()==WalletType.PASSWORD_MNEMONIC.getValue()){
            wallet = createPasswordMnemonicWallet(walletVO);
        }else{
            return Result.error("钱包类型错误");
        }
        return Result.OK(wallet);
    }


    private Wallet createNormalWallet(WalletVO walletVO) {
        KeyPair keyPair = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();
        Wallet wallet = new Wallet();
        wallet.setName(walletVO.getName());
        wallet.setPublicKeyHex(CryptoUtil.bytesToHex(publicKey.getEncoded()));
        wallet.setPrivateKeyHex(CryptoUtil.bytesToHex(privateKey.getEncoded()));
        wallet.setWalletType(WalletType.COMMON.getValue());
        return wallet;
    }

    private Wallet createPasswordWallet(WalletVO walletVO) {
        // 验证密码
        if (walletVO.getPassword() == null || walletVO.getPassword().isEmpty()) {
            throw new IllegalArgumentException("创建密码钱包需要提供密码");
        }

        KeyPair keyPair = CryptoUtil.ECDSASigner.generateKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();

        // 使用密码加密私钥
        String encryptedPrivateKey = CryptoUtil.encryptWithPassword(
                privateKey.getEncoded(),
                walletVO.getPassword()
        );
        Wallet wallet = new Wallet();
        wallet.setName(walletVO.getName());
        wallet.setPublicKeyHex(CryptoUtil.bytesToHex(publicKey.getEncoded()));
        wallet.setPrivateKeyHex(encryptedPrivateKey);
        wallet.setWalletType(WalletType.PASSWORD.getValue());
        wallet.setPasswordHash(CryptoUtil.hashPassword(walletVO.getPassword())); // 存储密码哈希用于验证
        return wallet;
    }




    /**
     * 助记词钱包（通常指基于助记词机制的区块链钱包）的核心作用是通过助记词这一易记形式，实现对数字资产的安全管理、备份与恢复，其核心价值体现在以下几个方面：
     * 1. 替代复杂私钥，简化资产控制权
     * 区块链中，数字资产（如比特币、以太坊等）的所有权由 “私钥” 决定，私钥是一串随机的长字符串（如 64 位十六进制数），难以记忆和手动记录。
     * 助记词通过特定算法（如 BIP-39 标准）将私钥转化为 12/18/24 个常见单词的序列，大幅降低了用户记录和保存的难度，同时助记词与私钥完全等价—— 拥有助记词即可推导出私钥，进而完全掌控对应钱包中的所有资产。
     * 2. 唯一且不可替代的备份工具
     * 助记词是钱包的 “终极备份”。当钱包软件损坏、设备丢失（如手机损坏、被盗）或需要更换设备时，用户只需在新设备的钱包中输入正确的助记词，即可重新生成对应的私钥，完整恢复钱包中的所有资产（包括地址、余额、交易记录等）。
     * 这意味着：只要助记词未丢失，无论钱包软件或设备出现任何问题，资产都能通过助记词找回。
     * 3. 跨平台 / 客户端的统一恢复入口
     * 基于同一助记词标准（如 BIP-39）的钱包，支持在不同品牌、不同类型的客户端（如手机 App、电脑软件、硬件钱包等）中恢复。
     * 例如，用户在 A 品牌钱包中生成的助记词，可在 B 品牌钱包中输入并恢复资产，无需依赖特定平台，提升了资产管理的灵活性。
     * 4. 体现区块链的去中心化特性
     * 助记词由用户自行生成和保管，无需依赖银行、交易所等第三方机构。用户完全掌控助记词，即完全掌控资产，避免了第三方平台倒闭、被黑客攻击或监管冻结带来的资产风险，体现了区块链 “去中心化” 的核心理念。
     * 关键提醒
     * 助记词的安全性直接决定资产安全：
     *
     * 一旦泄露，任何人都可通过助记词转移资产；
     * 一旦丢失且无备份，资产将永久无法找回。
     *
     * 因此，助记词需离线手写备份（避免拍照、截图），存放在安全地点，且不可泄露给任何人。
     *
     * 简言之，助记词钱包的核心作用是：用易记的单词序列替代复杂私钥，让用户安全、便捷地掌控数字资产，并实现跨场景的备份与恢复。
     * @param walletVO
     * @return
     */
    private Wallet createMnemonicWallet(WalletVO walletVO) {
        String password = walletVO.getPassword();

        byte[] initialEntropy = new byte[16];
        SecureRandomUtils.secureRandom().nextBytes(initialEntropy);
        String mnemonic = MnemonicUtils.generateMnemonic(initialEntropy);
        byte[] seed = MnemonicUtils.generateSeed(mnemonic, null);
        KeyPair keyPair = CryptoUtil.ECDSASigner.generateKeyPairFromSeed(CryptoUtil.applySHA256(seed));
        PublicKey aPublic = keyPair.getPublic();
        PrivateKey aPrivate = keyPair.getPrivate();
        Wallet wallet = new Wallet();
        wallet.setName(walletVO.getName());

        return null;
    }


    //main
    //引入真正的随机性：密码学密钥必须由高质量的随机数生成，否则可能被攻击者预测。
    //作为种子生成的基础：后续的助记词（Mnemonic）、种子（Seed）、密钥对（KeyPair）都是基于这个初始熵衍生出来的。
    //16字节随机数) → 助记词 → 种子 → 私钥 → 公钥




    private Wallet createPasswordMnemonicWallet(WalletVO walletVO) {

        return null;
    }
    // 用于管理钱包级别的锁
    private final ConcurrentHashMap<String, Object> walletLocks = new ConcurrentHashMap<>();

    //缓存已经使用的UTXO 测试
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<String>> walletUsedUTXOs = new ConcurrentHashMap<>();



    public Result<String> buildWalletUTXO(BuildWalletUTXODTO buildWalletUTXODTO) {
        // 常量定义：避免硬编码，提高可维护性
        final int PAGE_SIZE = 5000; // 分页大小，5000更符合常见分页逻辑
        final int RETRY_COUNT = 3; // 重试次数

        WalletStorage walletStorage = WalletStorage.getInstance();
        String walletName = buildWalletUTXODTO.getWalletName();
        Wallet wallet = walletStorage.getWallet(walletName);

        if (wallet == null) {
            log.error("钱包不存在: {}", walletName);
            return Result.error("钱包不存在");
        }

        try {
            // 1. 准备钱包相关的脚本哈希（支持P2PKH和P2WPKH两种类型）
            byte[] publicKey = CryptoUtil.hexToBytes(wallet.getPublicKeyHex());

            // P2PKH脚本哈希
            ScriptPubKey p2PKH = ScriptPubKey.createP2PKH(publicKey);
            byte[] p2pkhScriptHash = calculateScriptHash(p2PKH);
            log.info("构建钱包UTXO -> P2PKH脚本: {}", p2PKH.toScripString());

            // P2WPKH脚本哈希（原代码遗漏处理，此处补充）
            byte[] p2wpkhPubKeyHash = CryptoUtil.ECDSASigner.createP2WPKHByPK(publicKey);
            ScriptPubKey p2WPKH = ScriptPubKey.createP2WPKH(p2wpkhPubKeyHash);
            byte[] p2wpkhScriptHash = calculateScriptHash(p2WPKH);
            log.info("构建钱包UTXO -> P2WPKH脚本: {}", p2WPKH.toScripString());

            // 2. 初始化存储容器（使用普通HashSet+同步，比CopyOnWriteArraySet更高效）
            HashSet<String> walletUTXOs = new HashSet<>();
            long totalBalance = 0;

            // 3. 封装分页查询逻辑为工具方法，避免代码重复
            totalBalance += queryAndCollectUTXOs(
                    p2pkhScriptHash,
                    PAGE_SIZE,
                    RETRY_COUNT,
                    walletUTXOs,
                    "P2PKH"
            );

            totalBalance += queryAndCollectUTXOs(
                    p2wpkhScriptHash,
                    PAGE_SIZE,
                    RETRY_COUNT,
                    walletUTXOs,
                    "P2WPKH"
            );

            // 4. 批量保存UTXO并更新钱包余额（原子操作）
            wallet.setBalance(totalBalance);
            walletStorage.addWallet(wallet);
            walletStorage.saveWalletUTXOBatch(walletName, walletUTXOs);

            log.info("钱包中的UTXO:{}",walletUTXOs);

            log.info("钱包UTXO构建完成 -> 钱包名: {}, 总UTXO数: {}, 总余额: {}",
                    walletName, walletUTXOs.size(), totalBalance);
            return Result.ok("钱包UTXO构建完成，共找到" + walletUTXOs.size() + "个UTXO");

        } catch (Exception e) {
            log.error("构建钱包UTXO失败 -> 钱包名: {}", walletName, e);
            return Result.error("构建UTXO失败：" + e.getMessage());
        }
    }

    /**
     * 计算脚本哈希（抽取为工具方法，避免重复代码）
     */
    private byte[] calculateScriptHash(ScriptPubKey scriptPubKey) {
        byte[] scriptBytes = scriptPubKey.serialize();
        return CryptoUtil.applyRIPEMD160(CryptoUtil.applySHA256(scriptBytes));
    }

    /**
     * 分页查询并收集指定脚本哈希的UTXO
     * @param scriptHash 脚本哈希
     * @param pageSize 分页大小
     * @param retryCount 重试次数
     * @param utxoSet 收集UTXO的集合
     * @param scriptType 脚本类型（用于日志）
     * @return 该脚本类型对应的总余额
     */
    private long queryAndCollectUTXOs(byte[] scriptHash, int pageSize, int retryCount,
                                      Set<String> utxoSet, String scriptType) {
        String cursor = null;
        boolean hasMore = true;
        long typeBalance = 0;
        int totalFetched = 0;

        while (hasMore) {
            PageResult<UTXOSearch> pageResult = null;
            // 带重试的分页查询
            for (int i = 0; i < retryCount; i++) {
                try {
                    pageResult = blockChainService.selectUtxoAmountsByScriptHash(
                            scriptHash, pageSize, cursor);
                    break; // 成功查询则退出重试
                } catch (Exception e) {
                    log.warn("查询{} UTXO失败（第{}次重试）-> cursor: {}",
                            scriptType, i + 1, cursor, e);
                    if (i == retryCount - 1) {
                        throw new RuntimeException("超过最大重试次数，查询" + scriptType + " UTXO失败", e);
                    }
                    // 重试前短暂休眠
                    try { Thread.sleep(500 * (i + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }

            if (pageResult == null) {
                log.error("{} UTXO查询返回空结果 -> cursor: {}", scriptType, cursor);
                break;
            }

            UTXOSearch searchResult = pageResult.getData();
            if (searchResult == null) {
                log.warn("{} UTXO分页数据为空 -> cursor: {}", scriptType, cursor);
                break;
            }

            // 收集UTXO并累加余额
            Set<String> currentPageUTXOs = searchResult.getUtxos();
            if (currentPageUTXOs != null && !currentPageUTXOs.isEmpty()) {
                synchronized (utxoSet) { // 同步操作，避免并发问题
                    utxoSet.addAll(currentPageUTXOs);
                }
                typeBalance += searchResult.getTotal();
                totalFetched += currentPageUTXOs.size();
                log.info("{} UTXO分页处理完成 -> 本次获取: {}, 累计: {}, 余额: {}",
                        scriptType, currentPageUTXOs.size(), totalFetched, typeBalance);
            }

            // 更新分页状态
            hasMore = !pageResult.isLastPage();
            cursor = pageResult.getLastKey();
        }

        log.info("{} UTXO查询完成 -> 总数量: {}, 总余额: {}",
                scriptType, totalFetched, typeBalance);
        return typeBalance;
    }






    public Result getBalance(WalletBalanceVO walletBalanceVO) {
        WalletStorage instance = WalletStorage.getInstance();
        Wallet wallet = instance.getWallet(walletBalanceVO.getWalletName());
        return Result.ok(wallet);
    }


    // 计算可用余额
    private long calculateAvailableBalance(Set<String> utxoKeys) {
        long total = 0;
        for (String utxoKey : utxoKeys) {
            UTXO utxo = blockChainService.getUTXO(utxoKey);
            if (utxo != null) {
                total += utxo.getValue();
            }
        }
        return total;
    }















    /**
     * 创建交易  支持所有脚本类型UTXO（P2PKH/P2SH/P2WPKH/P2WSH）
     * @param transferVO 转账参数
     * @return 交易DTO
     */
    public Result<TransactionDTO> createTransaction(TransferVO transferVO) {
        String walletName = transferVO.getWalletName();
        try {
            WalletStorage instance = WalletStorage.getInstance();
            Set<String> walletUTXOs = instance.getWalletUTXOs(walletName);
            Wallet wallet = instance.getWallet(walletName);
            long availableBalance = wallet.getBalance();
            if (availableBalance < transferVO.getAmount()) {
                return Result.error("余额不足");
            }
            String toAddress = transferVO.getToAddress();
            // 统一创建交易，不再区分地址类型
            Transaction transaction = createUniversalTransaction(transferVO, toAddress, walletUTXOs);
            boolean b = blockChainService.verifyAndAddTradingPool(transaction);
            if (!b) {
                return Result.error("交易创建失败");
            }
            TransactionDTO transactionDTO = convertTransactionDTO(transaction);
            return Result.OK(transactionDTO);
        } catch (UnsupportedAddressException | InsufficientFundsException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            return Result.error("交易创建失败: " + e.getMessage());
        }
    }

    /**
     * 通用交易创建方法，根据UTXO脚本类型动态构造验证数据
     */
    private Transaction createUniversalTransaction(TransferVO transferVO, String toAddress, Set<String> walletUTXOs)
            throws UnsupportedAddressException, InsufficientFundsException {
        WalletStorage instance = WalletStorage.getInstance();
        Wallet wallet = instance.getWallet(transferVO.getWalletName());
        String publicKeyHex = wallet.getPublicKeyHex();
        String privateKeyHex = wallet.getPrivateKeyHex();

        String publicKeyHex1 = wallet.getPublicKeyHex1();
        String privateKeyHex1 = wallet.getPrivateKeyHex1();

        String publicKeyHex2 = wallet.getPublicKeyHex2();
        String privateKeyHex2 = wallet.getPrivateKeyHex2();

        //创建赎回脚本
        byte[] pubKey1 = CryptoUtil.hexToBytes(publicKeyHex);
        byte[] pubKey2 = CryptoUtil.hexToBytes(publicKeyHex1);
        byte[] pubKey3 = CryptoUtil.hexToBytes(publicKeyHex2);
        List<byte[]> pubKeys = Arrays.asList(
                pubKey1,
                pubKey2,
                pubKey3
        );
        ScriptPubKey redeemScript = ScriptPubKey.createMultisig(2, pubKeys);
        log.info("创建赎回脚本: {}", redeemScript.toScripString());

        byte[] publicKeyBytes = CryptoUtil.hexToBytes(publicKeyHex);//发送者的公钥

        byte[] privateKeyBytes = CryptoUtil.hexToBytes(privateKeyHex);
        PrivateKey privateKey = CryptoUtil.ECDSASigner.bytesToPrivateKey(privateKeyBytes);
        PublicKey publicKey = CryptoUtil.ECDSASigner.bytesToPublicKey(publicKeyBytes);

        // 接收地址处理
        byte[] toAddressHash = CryptoUtil.ECDSASigner.getAddressHash(toAddress);
        AddressType toAddressType = CryptoUtil.ECDSASigner.getAddressType(toAddress);

        // 交易基础信息
        Transaction transaction = new Transaction();
        List<TXInput> txInputs = new ArrayList<>();
        List<Witness> witnesses = new ArrayList<>(); // 隔离见证数据列表
        //缓存UTXO
        HashMap<String, UTXO> UTXOHashMap = new HashMap<>();
        HashSet<String> usedUTXOs = new HashSet<>();
        long totalInputAmount = 0L;
        long amountToPay = transferVO.getAmount();
        boolean enoughFunds = false;

        // 迭代处理UTXO
        Iterator<String> utxoIterator = walletUTXOs.iterator();
        while (utxoIterator.hasNext() && !enoughFunds) {
            List<String> batchUTXOs = new ArrayList<>();
            int count = 0;
            while (utxoIterator.hasNext() && count < 50) {
                batchUTXOs.add(utxoIterator.next());
                count++;
            }
            for (String utxoKey : batchUTXOs) {
                UTXO utxo = blockChainService.getUTXO(utxoKey);
                UTXOHashMap.put(utxoKey, utxo);
                if (utxo == null) continue;
                // 获取UTXO的脚本类型（核心：根据UTXO脚本类型处理）
                ScriptPubKey utxoScriptPubKey = utxo.getScriptPubKey();

                // 累加金额并标记使用的UTXO
                usedUTXOs.add(utxoKey);
                totalInputAmount += utxo.getValue();

                // 构建交易输入
                TXInput txInput = new TXInput();
                txInput.setTxId(utxo.getTxId());
                txInput.setVout(utxo.getVout());

                txInputs.add(txInput);
                // 检查金额是否足够
                if (totalInputAmount >= amountToPay) {
                    enoughFunds = true;
                    break;
                }
            }
        }
        // 资金不足校验
        if (!enoughFunds) {
            throw new InsufficientFundsException("可用余额不足");
        }
        // 设置交易输入
        transaction.setInputs(txInputs);
        log.info("交易输入构建完毕: {}", txInputs);

        // 构建交易输出
        List<TXOutput> txOutputs = new ArrayList<>();
        // 主输出（支付给接收地址）
        TXOutput mainOutput = new TXOutput();
        mainOutput.setValue(amountToPay);
        mainOutput.setScriptPubKey(blockChainService.createScriptPubKey(toAddressType, toAddressHash));
        txOutputs.add(mainOutput);
        // 计算手续费和找零
        calculateAndAddChange(transaction, txOutputs, totalInputAmount, amountToPay, publicKeyBytes, ScriptType.P2WSH.getValue());
        transaction.setOutputs(txOutputs);
        log.info("交易输出构建完毕: {}", txOutputs);

        //构建交易的输入的解锁脚本或者见证数据
        for (int i = 0; i < txInputs.size(); i++) {
            TXInput txInput = txInputs.get(i);
            UTXO utxo = UTXOHashMap.get(getUTXOKey(txInput.getTxId(), txInput.getVout()));
            if (utxo == null){
                throw new InsufficientFundsException("UTXO不存在");
            }
            ScriptPubKey scriptPubKey = utxo.getScriptPubKey();
            int scriptType = scriptPubKey.getType();
            byte[] sigHash = generateSigHash(transaction, i, utxo, scriptType);
            log.info("生成需要签名数据: {}", CryptoUtil.bytesToHex(sigHash));
            byte[] originalSignature = CryptoUtil.ECDSASigner.applySignature(privateKey, sigHash);
            byte[] signature = SegWitUtils.createSigHashType(originalSignature, SigHashType.ALL);
            if (isSegWitType(scriptType)) {
                log.info("UTXO是隔离见证交易发行的 构建隔离见证数据: {}", CryptoUtil.bytesToHex(signature));
                // 隔离见证类型：构建见证数据
                Witness witness = createWitness(scriptType, signature, publicKeyBytes, scriptPubKey,null,null);
                witnesses.add(i,witness);
                txInput.setScriptSig(null); // 隔离见证输入的scriptSig为空
            }else {
                //普通交易发行的UTXO
                ScriptSig scriptSig = createScriptSig(scriptType, signature, publicKeyBytes, scriptPubKey,null,null);
                txInput.setScriptSig(scriptSig);
            }
        }
        if (!witnesses.isEmpty()) {
            transaction.setWitnesses(witnesses);
            transaction.setVersion(2); // 隔离见证交易版本为2
        } else {
            transaction.setVersion(1); // 普通交易版本为1
        }
        // 交易最终属性设置
        transaction.setTime(System.currentTimeMillis() / 1000);
        transaction.setSize(transaction.calculateTotalSize());
        transaction.calculateWeight();
        transaction.setTxId(Transaction.calculateTxId(transaction));
        if (!witnesses.isEmpty()) {
            transaction.setWtxId(Transaction.calculateWtxId(transaction));
            log.info("隔离见证交易ID: {}", CryptoUtil.bytesToHex(transaction.getWtxId()));
        }
        log.info("交易ID: {}", CryptoUtil.bytesToHex(transaction.getTxId()));

        // 移除使用过的UTXO  或者标记已经使用
        instance.removeWalletUTXOBatch(wallet.getName(), usedUTXOs);
        return transaction;
    }

    // ------------------------------ 辅助方法 ------------------------------

    /**
     * 判断UTXO脚本类型是否为隔离见证
     */
    private boolean isSegWitType(int scriptType) {
        return scriptType == ScriptType.P2WPKH.getValue() || scriptType == ScriptType.P2WSH.getValue();
    }




    public Witness createWitness(int scriptTypeInt, byte[] signature, byte[] pubKey, ScriptPubKey utxoScript, List<byte[]> signatures, Script redeemScript  ) {
        Witness witness = new Witness();
        ScriptType scriptType = ScriptType.valueOf(scriptTypeInt);
        if (scriptType == null) {
            throw new UnsupportedAddressException("不支持的隔离见证类型: " + scriptTypeInt);
        }
        switch (scriptType) {
            case P2WPKH:
                witness.addItem(signature);
                witness.addItem(pubKey);
                break;
            case P2WSH:
                //[
                //  OP_0,  // 占位符（兼容CHECKMULTISIG的历史bug）
                //  <签名1>,  // 公钥1对应的私钥签名
                //  <签名2>,  // 公钥2对应的私钥签名
                //  <见证脚本>  // 即2 <公钥1> <公钥2> <公钥3> 3 CHECKMULTISIG
                //]
                ScriptSig p2WSH = ScriptSig.createP2WSH(signatures, redeemScript);
                List<Script.ScriptElement> elements = p2WSH.getElements();
                for (Script.ScriptElement element : elements) {
                    if (element.isOpCode()) {
                        witness.addItem(ByteUtils.intToBytes(element.getOpCode()));
                    } else {
                        witness.addItem(element.getData());
                    }
                }
                break;
            default:
                throw new UnsupportedAddressException("不支持的隔离见证类型: " + scriptTypeInt);
        }
        return witness;
    }


    /**
     * 创建普通交易的解锁脚本
     */
    private ScriptSig createScriptSig(int scriptType, byte[] signature, byte[] pubKey, ScriptPubKey utxoScript, List<byte[]> signatures, Script redeemScript) {
        switch (ScriptType.valueOf(scriptType)) {
            case P2PKH:
                return new ScriptSig(signature, pubKey);
            case P2SH:
                return ScriptSig.createP2SH(signatures, redeemScript);
            default:
                throw new UnsupportedAddressException("不支持的普通脚本类型: " + scriptType);
        }
    }

    /**
     * 生成签名哈希
     */
    private byte[] generateSigHash(Transaction tx, int inputIndex, UTXO utxo, int scriptType) {
        return blockChainService.createWitnessSignatureHash(tx, inputIndex, utxo.getValue(), SigHashType.ALL);
/*        if (isSegWitType(scriptType)) {
            return blockChainService.createWitnessSignatureHash(tx, inputIndex, utxo.getValue(), SigHashType.ALL);
        } else {
            return CryptoUtil.applySHA256(SerializeUtils.serialize(utxo));
        }*/
    }


    /**
     * 创建输出锁定脚本
     */


    /**
     * 计算并添加找零输出
     */
    private void calculateAndAddChange(Transaction tx, List<TXOutput> outputs, long totalInput, long amount, byte[] pubKeyBytes, int scriptType) {
        long size = tx.calculateTotalSize();
        long fee = size * 1000; // 每字节手续费
        long change = totalInput - amount - fee;
        if (change <= 0) return;

        // 找零地址类型与发送者UTXO类型一致
        AddressType changeType = isSegWitType(scriptType) ? AddressType.P2WPKH : AddressType.P2PKH;
        byte[] changeHash = isSegWitType(scriptType) ? CryptoUtil.ECDSASigner.createP2WPKHByPK(pubKeyBytes) : CryptoUtil.ECDSASigner.createP2PKHByPK(pubKeyBytes);
        TXOutput changeOutput = new TXOutput();
        changeOutput.setValue(change);
        changeOutput.setScriptPubKey(blockChainService.createScriptPubKey(changeType, changeHash));
        outputs.add(changeOutput);
    }

}
