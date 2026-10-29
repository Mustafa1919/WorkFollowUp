export const PRIVACY_NOTICE_VERSION = '2026-10-29'

export const PRIVACY_NOTICE_SECTIONS: { heading: string; paragraphs: string[] }[] = [
  {
    heading: 'Veri sorumlusu',
    paragraphs: [
      '6698 sayılı Kişisel Verilerin Korunması Kanunu ("KVKK") kapsamında veri sorumlusu, WorkFollowUp uygulamasını geliştiren ve işleten Mustafa Şenyurt\'tur (gerçek kişi).',
      'İletişim: ce.mustafa.senyurt@gmail.com',
    ],
  },
  {
    heading: 'İşlenen kişisel veriler',
    paragraphs: [
      'Kimlik ve iletişim: ad soyad, e-posta adresi.',
      'Hesap güvenliği: parolanız (yalnızca geri döndürülemez özeti), oturum ve kişisel erişim anahtarlarınızın özetleri, başarısız giriş denemelerinde e-posta adresiniz ve IP adresiniz.',
      'İçerik: çalışma alanlarında oluşturduğunuz ya da size atanan görevler, yorumlar, toplantılar, hedefler, raporlar ve gönderdiğiniz geri bildirimler.',
      'Kullanım ve hata kayıtları: ziyaret ettiğiniz uygulama sayfası, oluşan hatanın türü ve mesajı, kullanıcı ve çalışma alanı kimliği, zaman bilgisi.',
      'Entegrasyonlar: çalışma alanı yöneticisi bağladıysa GitHub olaylarındaki commit ve pull request bilgileri ile Slack bildirim adresi (şifreli saklanır).',
      'Tarayıcınızda: oturum çerezi, oturum için e-posta adresiniz ve tercih bilgileri (ör. tanıtım turunun görülmesi, masaüstü bildirimi ayarı).',
    ],
  },
  {
    heading: 'İşleme amaçları',
    paragraphs: [
      'Hesabınızı oluşturmak ve hizmeti sunmak; çalışma alanı, görev ve toplantı özelliklerini çalıştırmak.',
      'Hesap ve sistem güvenliğini sağlamak, kötüye kullanımı (ör. parola deneme saldırıları) önlemek.',
      'Davet, doğrulama, parola sıfırlama ve bildirim e-postalarını göndermek.',
      'Hataları tespit edip gidermek ve hizmeti geliştirmek.',
    ],
  },
  {
    heading: 'Hukuki sebepler',
    paragraphs: [
      'Kişisel verileriniz KVKK m. 5/2-c (bir sözleşmenin kurulması veya ifasıyla doğrudan ilgili olması) ve m. 5/2-f (temel hak ve özgürlüklerinize zarar vermemek kaydıyla veri sorumlusunun meşru menfaati: güvenlik, hata giderme ve hizmetin geliştirilmesi) hukuki sebeplerine dayanılarak işlenir.',
      'Bu işlemeler için açık rızanız talep edilmez.',
    ],
  },
  {
    heading: 'Aktarım',
    paragraphs: [
      'Verileriniz Türkiye\'de bulunan sunucuda saklanır ve üçüncü kişilere satılmaz.',
      'E-posta bildirimleri bir e-posta gönderim hizmeti aracılığıyla iletilir; bu hizmete yalnızca alıcı adresi ve e-postanın içeriği aktarılır.',
      'Çalışma alanı yöneticisi Slack ya da GitHub entegrasyonu kurarsa, ilgili bildirim içeriği bu hizmetlere iletilir; bu hizmetlerin sunucuları yurt dışında olabilir ve aktarım KVKK m. 9 kapsamında yapılır.',
      'Hukuki bir yükümlülük doğarsa, veriler yetkili kamu kurum ve kuruluşlarıyla paylaşılabilir.',
    ],
  },
  {
    heading: 'Toplama yöntemi',
    paragraphs: [
      'Verileriniz kayıt formu ve uygulamanın kullanımı sırasında elektronik ortamda, kısmen otomatik yollarla (kullanım ve hata kayıtları, güvenlik sayaçları) ve çalışma alanının bağladığı entegrasyonlar üzerinden toplanır.',
    ],
  },
  {
    heading: 'Saklama süreleri',
    paragraphs: [
      'Hesap ve içerik verileri hesabınız açık olduğu sürece saklanır.',
      'Oturum kayıtları en fazla 7 gün, başarısız giriş sayaçları 15 dakika tutulur.',
      'Kullanım ve hata kayıtları en fazla 12 ay saklanır.',
      'Süresi dolan ya da silinmesini talep ettiğiniz veriler silinir veya anonim hale getirilir.',
    ],
  },
  {
    heading: 'Haklarınız (KVKK m. 11)',
    paragraphs: [
      'Kişisel verilerinizin işlenip işlenmediğini öğrenme, işlenmişse buna ilişkin bilgi talep etme; işlenme amacını ve amacına uygun kullanılıp kullanılmadığını öğrenme; yurt içinde veya yurt dışında aktarıldığı üçüncü kişileri bilme haklarına sahipsiniz.',
      'Eksik veya yanlış işlenmişse düzeltilmesini, KVKK m. 7 şartları çerçevesinde silinmesini veya yok edilmesini ve bu işlemlerin aktarılan üçüncü kişilere bildirilmesini isteyebilirsiniz.',
      'İşlenen verilerin münhasıran otomatik sistemlerle analiz edilmesi suretiyle aleyhinize bir sonuç ortaya çıkmasına itiraz edebilir, kanuna aykırı işleme sebebiyle zarara uğramanız hâlinde zararın giderilmesini talep edebilirsiniz.',
    ],
  },
  {
    heading: 'Başvuru',
    paragraphs: [
      'Haklarınıza ilişkin taleplerinizi, kayıtlı e-posta adresinizden ce.mustafa.senyurt@gmail.com adresine yazarak iletebilirsiniz.',
      'Başvurunuz, niteliğine göre en geç 30 gün içinde ücretsiz olarak sonuçlandırılır (KVKK m. 13).',
    ],
  },
]
