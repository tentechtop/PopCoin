package com.pop.popcoinsystem.application.service;


import com.pop.popcoinsystem.data.vo.result.Result;
import com.pop.popcoinsystem.service.UTXOService;
import com.pop.popcoinsystem.util.CryptoUtil;
import com.pop.popcoinsystem.util.MnemonicUtils;
import com.pop.popcoinsystem.util.SecureRandomUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
public class WalletService {
    @Resource
    private UTXOService utxoService;

    private static WalletStorage walletStorage;
    public static Wallet walleta;
    public static Wallet walletb;


    static {
        log.info("钱包初始化 WalletService init");
        //获取公钥和私钥  初始化两个钱包 A B
        //先从数据库中获取公钥和私钥
        walletStorage = WalletStorage.getInstance();
        walleta = walletStorage.getWallet("wallet-a");
        log.info("钱包A"+walleta);
        if (walleta == null){
            KeyPair keyPairA = CryptoUtil.ECDSASigner.generateKeyPair();
            PrivateKey privateKey = keyPairA.getPrivate();
            PublicKey publicKey = keyPairA.getPublic();
            walleta = new Wallet();
            walleta.setName("wallet-a");
            walleta.setPublicKeyHex(CryptoUtil.bytesToHex(publicKey.getEncoded()));
            walleta.setPrivateKeyHex(CryptoUtil.bytesToHex(privateKey.getEncoded()));
            walletStorage.addWallet(walleta);
        }
        walletb = walletStorage.getWallet("wallet-b");
        log.info("钱包B"+walletb);
        if (walletb == null){
            KeyPair keyPairB = CryptoUtil.ECDSASigner.generateKeyPair();
            PrivateKey privateKey = keyPairB.getPrivate();
            PublicKey publicKey = keyPairB.getPublic();
            walletb = new Wallet();
            walletb.setName("wallet-b");
            walletb.setPublicKeyHex(CryptoUtil.bytesToHex(publicKey.getEncoded()));
            walletb.setPrivateKeyHex(CryptoUtil.bytesToHex(privateKey.getEncoded()));
            walletStorage.addWallet(walletb);
        }
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
    }

    private Wallet createPasswordMnemonicWallet(WalletVO walletVO) {

        return null;
    }





}
